/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

public class CommonThreadStateRenderer {
  /**
   * Amount of time a rule can run before we render it with as a warning.
   */
  private static final long WARNING_THRESHOLD_MS = 15000;

  /**
   * Amount of time a rule can run before we render it with as an error.
   */
  private static final long ERROR_THRESHOLD_MS = 30000;

  private final Ansi ansi;
  private final Function<Long, String> formatTimeFunction;
  private final long currentTimeMs;
  private final ImmutableList<Long> sortedThreadIds;

  public CommonThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      Iterable<Long> threadIds) {
    this.ansi = ansi;
    this.formatTimeFunction = formatTimeFunction;
    this.currentTimeMs = currentTimeMs;
    this.sortedThreadIds = FluentIterable.from(threadIds)
        .toSortedList(Ordering.natural());
  }

  public ImmutableList<Long> getSortedThreadIds() {
    return sortedThreadIds;
  }

  public String renderLine(
      Optional<BuildTarget> buildTarget,
      Optional<? extends AbstractBuckEvent> startEvent,
      Optional<? extends LeafEvent> runningStep,
      Optional<String> stepCategory,
      Optional<String> placeholderStepInformation,
      long elapsedTimeMs,
      StringBuilder lineBuilder) {
    lineBuilder.delete(0, lineBuilder.length());
    lineBuilder.append(" |=> ");
    if (!startEvent.isPresent() || !buildTarget.isPresent()) {
      lineBuilder.append("IDLE");
      return ansi.asSubtleText(lineBuilder.toString());
    } else {
      lineBuilder.append(buildTarget.get());
      lineBuilder.append("...  ");
      lineBuilder.append(formatElapsedTime(elapsedTimeMs));

      if (runningStep.isPresent() && stepCategory.isPresent()) {
        lineBuilder.append(" (running ");
        lineBuilder.append(stepCategory.get());
        lineBuilder.append('[');
        lineBuilder.append(formatElapsedTime(currentTimeMs - runningStep.get().getTimestamp()));
        lineBuilder.append("])");

        if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
          return ansi.asErrorText(lineBuilder.toString());
        } else if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
          return ansi.asWarningText(lineBuilder.toString());
        } else {
          return lineBuilder.toString();
        }
      } else if (placeholderStepInformation.isPresent()) {
        lineBuilder.append(" (");
        lineBuilder.append(placeholderStepInformation.get());
        lineBuilder.append(')');
        return ansi.asSubtleText(lineBuilder.toString());
      } else {
        return lineBuilder.toString();
      }
    }
  }

  private String formatElapsedTime(long elapsedTimeMs) {
    return formatTimeFunction.apply(elapsedTimeMs);
  }
}
