/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.jdbc.cdc.postgres;

import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.Stage.ConfigIssue;
import com.streamsets.pipeline.api.Stage.Context;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.lib.jdbc.HikariPoolConfigBean;
import com.streamsets.pipeline.lib.jdbc.JdbcErrors;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_00;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_406;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_407;
import com.streamsets.pipeline.lib.jdbc.JdbcUtil;
import com.streamsets.pipeline.lib.jdbc.UtilsProvider;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import com.streamsets.pipeline.stage.origin.jdbc.cdc.SchemaAndTable;
import com.streamsets.pipeline.stage.origin.jdbc.cdc.SchemaTableConfigBean;
import static java.sql.DriverManager.getConnection;
import org.apache.commons.lang3.StringUtils;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public class PostgresCDCWalReceiver {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresCDCWalReceiver.class);
  private static final String TABLE_METADATA_TABLE_SCHEMA_CONSTANT = "table_schem";
  private static final String TABLE_METADATA_TABLE_NAME_CONSTANT = "table_name";
  public static final String SELECT_SLOT = "select * from pg_replication_slots where slot_name = ?";

  private final Properties properties;
  private final String uri;
  private final Context context;
  private final String slotName;
  private final DecoderValues outputPlugin;

  private String configuredPlugin;
  private String configuredSlotType;
  private Boolean slotActive;
  private String restartLsn;
  private String confirmedFlushLSN;
  private Connection connection = null;
  private PGReplicationStream stream;
  private List<SchemaAndTable> schemasAndTables;
  private PostgresCDCConfigBean configBean;
  private HikariPoolConfigBean hikariConfigBean;

  private final JdbcUtil jdbcUtil;

//  public PGReplicationStream getStream() {
//    return stream;
//  }

  public List<SchemaAndTable> getSchemasAndTables() {
    return schemasAndTables;
  }

  public Optional<List<ConfigIssue>> validateSchemaAndTables() {
    List<ConfigIssue> issues = new ArrayList<>();
    schemasAndTables = new ArrayList<>();
    for (SchemaTableConfigBean tables : configBean.baseConfigBean.schemaTableConfigs) {
      validateSchemaAndTable(tables).ifPresent(issues::add);
    }
    return Optional.ofNullable(issues);
  }

  private Optional<ConfigIssue> validateSchemaAndTable(SchemaTableConfigBean tables) {
    ConfigIssue issue = null;
    // Empty keys match ALL :(
    if (tables.schema.isEmpty() && tables.table.isEmpty()) {
      return Optional.ofNullable(issue);
    }
    Pattern p = StringUtils.isEmpty(tables.excludePattern) ? null : Pattern.compile(tables.excludePattern);
    try (ResultSet rs =
        jdbcUtil.getTableAndViewMetadata(connection, tables.schema, tables.table)) {
      while (rs.next()) {
        String schemaName = rs.getString(TABLE_METADATA_TABLE_SCHEMA_CONSTANT);
        String tableName = rs.getString(TABLE_METADATA_TABLE_NAME_CONSTANT);
        if (p == null || !p.matcher(tableName).matches()) {
          schemaName = schemaName.trim();
          tableName = tableName.trim();
          // Passed validation, added
          schemasAndTables.add(new SchemaAndTable(schemaName, tableName));
        }
      }
    } catch (SQLException e) {
      issue = context.createConfigIssue(Groups.CDC.name(), tables.schema, JdbcErrors.JDBC_66);
    }
    return Optional.ofNullable(issue);
  }

  public void createReplicationSlot(String slotName) throws StageException {
    try (PreparedStatement preparedStatement =
        connection.prepareStatement("SELECT * FROM pg_create_logical_replication_slot(?, ?)")) {
      preparedStatement.setString(1, slotName);
      preparedStatement.setString(2, outputPlugin.getLabel());
      try (ResultSet rs = preparedStatement.executeQuery()) {
        while (rs.next()) {
          if ( ! slotName.equals(rs.getString(1))) {
            throw new StageException(JDBC_407);
          }
          LOG.debug("Slot Name: " +  rs.getString(1) + " " + rs.getString(2));
        }
      }
    } catch (SQLException e) {
      throw new StageException(JDBC_00, e.getMessage(), e);
    }
  }

  public LogSequenceNumber createReplicationStream(String startOffset)
      throws StageException, InterruptedException, TimeoutException, SQLException {

    boolean newSlot = false;
    if ( ! doesReplicationSlotExists(slotName)) {
      createReplicationSlot(slotName);
      newSlot = true;
    }
    obtainReplicationSlotInfo(slotName);

    connection = getConnection(this.uri, this.properties);
    PGConnection pgConnection = connection.unwrap(PGConnection.class);

    ChainedLogicalStreamBuilder streamBuilder = pgConnection
        .getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName(slotName)
        .withSlotOption("include-xids", true)
        .withSlotOption("include-timestamp", true)
        .withSlotOption("include-lsn", true)
        .withStatusInterval(configBean.pollInterval, TimeUnit.SECONDS);

    if (newSlot) {
      // if the replication slot was just created, we need to set the start offset to the stream as postgress
      // does not know yet what is the starting point. we use the initial offset by configuration.
      LogSequenceNumber lsn = getLogSequenceNumber(startOffset);
      streamBuilder.withStartPosition(lsn);
    }

    stream = streamBuilder.start();

    LogSequenceNumber lsnStart = getCurrentLSN();

    LOG.debug("Starting fromLSN: {}", lsnStart);

    return lsnStart;
  }

  private LogSequenceNumber getLogSequenceNumber(String startOffset) {
    LogSequenceNumber lsn = null;

    switch(configBean.startValue) {

      case LATEST:
        //startOffset is always NULL when LATEST per PostgresCDCSource.validatePostgresCDCConfigBean()
        if (startOffset == null) {
          startOffset = confirmedFlushLSN;
        }
        LogSequenceNumber lsnStartOffset = LogSequenceNumber.valueOf(startOffset);
        LogSequenceNumber lsnConfirmedFlush = LogSequenceNumber.valueOf(confirmedFlushLSN);
        lsn = lsnStartOffset.asLong() > lsnConfirmedFlush.asLong() ?
            lsnStartOffset : lsnConfirmedFlush;
        break;

      case LSN:
        //startOffset is config.lsn
      case DATE:
        //startOffset is always 1L (which it is earliest avail)

        // is never NULL here
        if (startOffset == null) {
          startOffset = PostgresCDCSource.SEED_LSN;
        }
        lsn = LogSequenceNumber.valueOf(startOffset);
        break;

      default:
        //should throw exception
    }
    return lsn; //never NULL
  }

  public void dropReplicationSlot(String slotName)
      throws StageException
  {
    try (Connection localConnection = DriverManager.getConnection(
            hikariConfigBean.getConnectionString(),
            hikariConfigBean.getUsername().get(),
            hikariConfigBean.getPassword().get()
    )) {
      if (isReplicationSlotActive(slotName)) {
        try (PreparedStatement preparedStatement = localConnection.prepareStatement(
            "select pg_terminate_backend(active_pid) from pg_replication_slots "
                + "where active = true and slot_name = ?")) {
          preparedStatement.setString(1, slotName);
          preparedStatement.execute();
        }
        waitStopReplicationSlot(slotName);
      }

      try (PreparedStatement preparedStatement = localConnection
            .prepareStatement("select pg_drop_replication_slot(slot_name) "
                + "from pg_replication_slots where slot_name = ?")) {
          preparedStatement.setString(1, slotName);
          preparedStatement.execute();
      }
    } catch (SQLException e) {
      throw new StageException(JDBC_407, slotName, e);
    }
  }

  public  void obtainReplicationSlotInfo(String slotName) throws StageException {
    try {
      try (Connection localConnection = DriverManager.getConnection(
          hikariConfigBean.getConnectionString(),
          hikariConfigBean.getUsername().get(),
          hikariConfigBean.getPassword().get()
      )) {
        String sql = SELECT_SLOT;
        String flushedLabel = "confirmed_flush_lsn";
        boolean hasFlushLsn = false;
        try (PreparedStatement preparedStatement = localConnection
            .prepareStatement(sql)) {
          preparedStatement.setString(1, slotName);
          try (ResultSet rs = preparedStatement.executeQuery()) {

            ResultSetMetaData rsmd = rs.getMetaData();
            int columns = rsmd.getColumnCount();
            for (int x = 1; x <= columns; x++) {
              if (flushedLabel.equals(rsmd.getColumnName(x))) {
                hasFlushLsn=true;
                break;
              }
            }
            if (!hasFlushLsn) {
              LOG.debug("No column: confirmed_flush_lsn found. Using restart_lsn");
              flushedLabel="restart_lsn";
            }

            while (rs.next()) {
              this.configuredPlugin = rs.getString("plugin");
              this.configuredSlotType = rs.getString("slot_type");
              this.slotActive = rs.getBoolean("active");
              this.restartLsn = rs.getString("restart_lsn");
              this.confirmedFlushLSN = rs.getString(flushedLabel);
            }
          }
        }
      }

    } catch (SQLException e) {
      throw new StageException(JDBC_407, slotName, e);
    }
  }

  public  boolean isReplicationSlotActive(String slotName)
      throws StageException
  {
    obtainReplicationSlotInfo(slotName);
    return slotActive;
  }

  public boolean doesReplicationSlotExists(String slotName) throws
      StageException {
    obtainReplicationSlotInfo(slotName);
    // if replication slot does no exist we don't have a configured plugin
    return configuredPlugin != null;
  }

  private  void waitStopReplicationSlot(String slotName)
      throws StageException
  {
    long startWaitTime = System.currentTimeMillis();
    boolean stillActive;
    long timeInWait = 0;

    do {
      stillActive = isReplicationSlotActive(slotName);
      if (stillActive) {
        ThreadUtil.sleep(100L);
        timeInWait = System.currentTimeMillis() - startWaitTime;
      }
    } while (stillActive && timeInWait <= 30000);

    if (stillActive) {
      throw new StageException(JDBC_406, slotName);
    }
  }

  public void commitCurrentOffset() throws StageException {
    LogSequenceNumber lsn = getCurrentLSN();
    stream.setAppliedLSN(lsn);
    stream.setFlushedLSN(lsn);
    try {
      stream.forceUpdateStatus();
    } catch (SQLException e) {
      LOG.error("Error forcing update status: {}", e.getMessage());
      throw new StageException(JDBC_00, " forceUpdateStatus failed :"+e.getMessage(), e);
    }
  }

  public void closeConnection() throws SQLException {
    if (connection != null) {
      connection.close();
    }
  }

  public PostgresCDCWalReceiver(
      PostgresCDCConfigBean configBean,
      HikariPoolConfigBean hikariConfigBean,
      Stage.Context context
  ) throws StageException {
    this.jdbcUtil = UtilsProvider.getJdbcUtil();
    this.configBean = configBean;
    this.hikariConfigBean = hikariConfigBean;
    this.context = context;

    /* TODO resolve issue with using internal Jdbc Read only connection - didn't work
     with postgres replication connection - keeping HikariConfigBean for now */
    try {
      this.connection = getConnection(
          hikariConfigBean.getConnectionString(),
          hikariConfigBean.getUsername().get(),
          hikariConfigBean.getPassword().get());
    } catch (SQLException e) {
      throw new StageException(JDBC_00, e.getMessage(), e);
    }

    this.slotName = configBean.slot;
    this.outputPlugin = configBean.decoderValue;
    this.uri = hikariConfigBean.getConnectionString();
    this.configuredPlugin = null;
    this.configuredSlotType = null;
    this.slotActive = false;
    this.restartLsn = null ;
    this.confirmedFlushLSN = null ;

    this.properties = new Properties();
    PGProperty.USER.set(properties, hikariConfigBean.getUsername().get());
    PGProperty.PASSWORD.set(properties, hikariConfigBean.getPassword().get());
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, configBean.minVersion.getLabel());
    PGProperty.REPLICATION.set(properties, configBean.replicationType);
    PGProperty.PREFER_QUERY_MODE.set(properties, "simple");

  }

  public ByteBuffer readNonBlocking() throws SQLException {
    return stream.readPending();
  }

  public LogSequenceNumber getCurrentLSN() {
    return stream.getLastReceiveLSN();
  }

}
