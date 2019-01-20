/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

public class ClientSideBuildSlaveFinishedStatsEvent extends AbstractBuckEvent {
  private final BuildSlaveStats buildSlaveFinishedStats;

  public ClientSideBuildSlaveFinishedStatsEvent(BuildSlaveStats buildSlaveFinishedStats) {
    super(EventKey.unique());
    this.buildSlaveFinishedStats = buildSlaveFinishedStats;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  public BuildSlaveStats getBuildSlaveFinishedStats() {
    return buildSlaveFinishedStats;
  }
}
