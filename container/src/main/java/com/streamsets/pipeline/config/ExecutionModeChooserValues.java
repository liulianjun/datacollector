/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.config;

import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.base.BaseEnumChooserValues;

public class ExecutionModeChooserValues extends BaseEnumChooserValues {

  public ExecutionModeChooserValues() {
    super(ExecutionMode.class);
  }
}
