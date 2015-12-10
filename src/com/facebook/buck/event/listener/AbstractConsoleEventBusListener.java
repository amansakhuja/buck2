/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.i18n.NumberFormatter;
import com.facebook.buck.json.ParseBuckFileEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.ActionGraphEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;

import java.io.Closeable;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * Base class for {@link BuckEventListener}s responsible for outputting information about the
 * running build to {@code stderr}.
 */
public abstract class AbstractConsoleEventBusListener implements BuckEventListener, Closeable {
  private static final NumberFormatter TIME_FORMATTER = new NumberFormatter(
      new Function<Locale, NumberFormat>() {
        @Override
        public NumberFormat apply(Locale locale) {
          // Yes, this is the only way to apply and localize a pattern to a NumberFormat.
          NumberFormat numberFormat = NumberFormat.getIntegerInstance(locale);
          Preconditions.checkState(numberFormat instanceof DecimalFormat);
          DecimalFormat decimalFormat = (DecimalFormat) numberFormat;
          decimalFormat.applyPattern("0.0s");
          return decimalFormat;
        }
      });

  protected static final long UNFINISHED_EVENT_PAIR = -1;
  protected final Console console;
  protected final Clock clock;
  protected final Ansi ansi;
  private final Locale locale;

  @Nullable
  protected volatile ProjectBuildFileParseEvents.Started projectBuildFileParseStarted;
  @Nullable
  protected volatile ProjectBuildFileParseEvents.Finished projectBuildFileParseFinished;

  @Nullable
  protected volatile ProjectGenerationEvent.Started projectGenerationStarted;
  @Nullable
  protected volatile ProjectGenerationEvent.Finished projectGenerationFinished;

  @Nullable
  protected volatile ParseEvent.Started parseStarted;
  @Nullable
  protected volatile ParseEvent.Finished parseFinished;

  @Nullable
  protected volatile ActionGraphEvent.Started actionGraphStarted;
  @Nullable
  protected volatile ActionGraphEvent.Finished actionGraphFinished;

  @Nullable
  protected volatile BuildEvent.Started buildStarted;
  @Nullable
  protected volatile BuildEvent.Finished buildFinished;

  @Nullable
  protected volatile InstallEvent.Started installStarted;
  @Nullable
  protected volatile InstallEvent.Finished installFinished;

  protected AtomicReference<HttpArtifactCacheEvent.Scheduled> firstHttpCacheUploadScheduled =
      new AtomicReference<>();

  protected final AtomicInteger httpArtifactUploadsScheduledCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadsStartedCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadedCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadFailedCount = new AtomicInteger(0);

  @Nullable
  protected volatile HttpArtifactCacheEvent.Shutdown httpShutdownEvent;

  protected volatile Optional<Integer> ruleCount = Optional.absent();

  protected final AtomicInteger numRulesCompleted = new AtomicInteger();

  protected Optional<ProgressEstimator> progressEstimator = Optional.<ProgressEstimator>absent();

  public AbstractConsoleEventBusListener(Console console, Clock clock, Locale locale) {
    this.console = console;
    this.clock = clock;
    this.locale = locale;
    this.ansi = console.getAnsi();

    this.projectBuildFileParseStarted = null;
    this.projectBuildFileParseFinished = null;

    this.projectGenerationStarted = null;
    this.projectGenerationFinished = null;

    this.parseStarted = null;
    this.parseFinished = null;

    this.actionGraphStarted = null;
    this.actionGraphFinished = null;

    this.buildStarted = null;
    this.buildFinished = null;

    this.installStarted = null;
    this.installFinished = null;
  }

  public void setProgressEstimator(ProgressEstimator estimator) {
    progressEstimator = Optional.<ProgressEstimator>of(estimator);
  }

  protected String formatElapsedTime(long elapsedTimeMs) {
    return TIME_FORMATTER.format(locale, elapsedTimeMs / 1000.0);
  }

  protected Optional<Double> getApproximateBuildProgress() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getApproximateBuildProgress();
    } else {
      return Optional.<Double>absent();
    }
  }

  protected Optional<Double> getEstimatedProgressOfGeneratingProjectFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfGeneratingProjectFiles();
    } else {
      return Optional.<Double>absent();
    }
  }

  protected Optional<Double> getEstimatedProgressOfProcessingBuckFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfProcessingBuckFiles();
    } else {
      return Optional.<Double>absent();
    }
  }

  /**
   * Adds a line about a pair of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time.  Set this to a non-zero value if the
   *     event pair would contain another event.  For example, build time includes parse time, but
   *     to make the events easier to reason about it makes sense to pull parse time out of build
   *     time.
   * @param startEvent The started event.
   * @param finishedEvent The finished event.
   * @param lines The builder to append lines to.
   * @return The amount of time between start and finished if finished is present,
   *    otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      @Nullable BuckEvent startEvent,
      @Nullable BuckEvent finishedEvent,
      Optional<Double> progress,
      ImmutableList.Builder<String> lines) {
    long result = UNFINISHED_EVENT_PAIR;
    if (startEvent == null) {
      return result;
    }
    String parseLine = (finishedEvent != null ? "[-] " : "[+] ") + prefix + "...";
    long elapsedTimeMs;
    if (finishedEvent != null) {
      elapsedTimeMs = finishedEvent.getTimestamp() - startEvent.getTimestamp();
      parseLine += "FINISHED ";
      result = elapsedTimeMs;
      if (progress.isPresent()) {
        progress = Optional.<Double>of(Double.valueOf(1));
      }
    } else {
      elapsedTimeMs = currentMillis - startEvent.getTimestamp();
    }
    parseLine += formatElapsedTime(elapsedTimeMs - offsetMs);
    if (progress.isPresent()) {
      parseLine += " [" + Math.round(progress.get().doubleValue() * 100) + "%]";
    }
    if (suffix.isPresent()) {
      parseLine += " " + suffix.get();
    }
    lines.add(parseLine);

    return result;
  }

  /**
   * Formats a {@link ConsoleEvent} and adds it to {@code lines}.
   */
  protected void formatConsoleEvent(ConsoleEvent logEvent, ImmutableList.Builder<String> lines) {
    String formattedLine = "";
    if (logEvent.getLevel().equals(Level.INFO)) {
      formattedLine = logEvent.getMessage();
    } else if (logEvent.getLevel().equals(Level.WARNING)) {
      formattedLine = ansi.asWarningText(logEvent.getMessage());
    } else if (logEvent.getLevel().equals(Level.SEVERE)) {
      formattedLine = ansi.asHighlightedFailureText(logEvent.getMessage());
    }
    if (!formattedLine.isEmpty()) {
      // Split log messages at newlines and add each line individually to keep the line count
      // consistent.
      lines.addAll(Splitter.on("\n").split(formattedLine));
    }
  }

  @Subscribe
  public void commandStartedEvent(CommandEvent.Started startedEvent) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().setCurrentCommand(
          startedEvent.getCommandName(),
          startedEvent.getArgs());
    }
  }

  @Subscribe
  public void projectBuildFileParseStarted(ProjectBuildFileParseEvents.Started started) {
    projectBuildFileParseStarted = started;
  }

  @Subscribe
  public void projectBuildFileParseFinished(ProjectBuildFileParseEvents.Finished finished) {
    projectBuildFileParseFinished = finished;
  }

  @Subscribe
  public void projectGenerationStarted(ProjectGenerationEvent.Started started) {
    projectGenerationStarted = started;
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void projectGenerationProcessedTarget(ProjectGenerationEvent.Processed processed) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didGenerateProjectForTarget();
    }
  }

  @Subscribe
  public void projectGenerationFinished(ProjectGenerationEvent.Finished finished) {
    projectGenerationFinished = finished;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishProjectGeneration();
    }
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    parseStarted = started;
  }

  @Subscribe
  public void ruleParseFinished(ParseBuckFileEvent.Finished ruleParseFinished) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didParseBuckRules(ruleParseFinished.getNumRules());
    }
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    parseFinished = finished;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishParsing();
    }
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    actionGraphStarted = started;
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphFinished = finished;
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    buildStarted = started;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didStartBuild();
    }
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount = Optional.of(calculated.getNumRules());
    if (progressEstimator.isPresent()) {
      progressEstimator.get().setNumberOfRules(calculated.getNumRules());
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didStartRule();
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didResumeRule();
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didSuspendRule();
    }
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (finished.getStatus() != BuildRuleStatus.CANCELED) {
      if (progressEstimator.isPresent()) {
        progressEstimator.get().didFinishRule();
      }
      numRulesCompleted.getAndIncrement();
    }
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    buildFinished = finished;
    if (progressEstimator.isPresent()) {
      progressEstimator.get().didFinishBuild();
    }
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    installStarted = started;
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    installFinished = finished;
  }

  @Subscribe
  public void onHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      firstHttpCacheUploadScheduled.compareAndSet(null, event);
      httpArtifactUploadsScheduledCount.incrementAndGet();
    }
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      httpArtifactUploadsStartedCount.incrementAndGet();
    }
  }

  @Subscribe
  public void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      if (event.wasUploadSuccessful()) {
        httpArtifactUploadedCount.incrementAndGet();
      } else {
        httpArtifactUploadFailedCount.incrementAndGet();
      }
    }
  }

  @Subscribe
  public void onHttpArtifactCacheShutdownEvent(HttpArtifactCacheEvent.Shutdown event) {
    httpShutdownEvent = event;
  }

  protected int getHttpUploadFinishedCount() {
    return httpArtifactUploadedCount.get() + httpArtifactUploadFailedCount.get();
  }

  protected int getHttpUploadScheduledCount() {
    return httpArtifactUploadsScheduledCount.get();
  }

  protected Optional<String> renderHttpUploads() {
    int scheduled = httpArtifactUploadsScheduledCount.get();
    int complete = httpArtifactUploadedCount.get();
    int failed = httpArtifactUploadFailedCount.get();
    int uploading = httpArtifactUploadsStartedCount.get() - (complete + failed);
    int pending = scheduled - (uploading + complete + failed);
    if (scheduled > 0) {
      return Optional.of(
          String.format(
              "(%d COMPLETE/%d FAILED/%d UPLOADING/%d PENDING)",
              complete,
              failed,
              uploading,
              pending
          ));
    } else {
      return Optional.absent();
    }
  }

  @Override
  public void outputTrace(BuildId buildId) {}

  @Override
  public void close() throws IOException {
  }
}
