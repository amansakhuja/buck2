/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.ArtifactCacheEvent;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.LoggingArtifactCacheDecorator;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystemWatcher;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public final class Main {

  /**
   * Trying again won't help.
   */
  public static final int FAIL_EXIT_CODE = 1;

  /**
   * Trying again later might work.
   */
  public static final int BUSY_EXIT_CODE = 2;

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  private static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private final PrintStream stdOut;
  private final PrintStream stdErr;

  private static final Semaphore commandSemaphore = new Semaphore(1);

  private final Platform platform;

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private final class Daemon implements Closeable {

    private final Parser parser;
    private final EventBus fileEventBus;
    private final ProjectFilesystemWatcher filesystemWatcher;
    private final BuckConfig config;

    public Daemon(ProjectFilesystem projectFilesystem,
                  BuckConfig config,
                  Console console) throws IOException {
      this.config = config;
      this.parser = new Parser(projectFilesystem,
          new KnownBuildRuleTypes(),
          console,
          config.getPythonInterpreter());
      this.fileEventBus = new EventBus("file-change-events");
      this.filesystemWatcher = new ProjectFilesystemWatcher(
          projectFilesystem,
          fileEventBus,
          config.getIgnorePaths(),
          FileSystems.getDefault().newWatchService());
      fileEventBus.register(parser);
    }

    private Parser getParser() {
      return parser;
    }

    private void watchFileSystem() throws IOException {
      filesystemWatcher.postEvents();
    }

    public BuckConfig getConfig() {
      return config;
    }

    @Override
    public void close() throws IOException {
      filesystemWatcher.close();
    }
  }

  @Nullable private static Daemon daemon;

  private boolean isDaemon() {
    return Boolean.getBoolean("buck.daemon");
  }

  private Daemon getDaemon(ProjectFilesystem filesystem,
                           BuckConfig config,
                           Console console) throws IOException {
    if (daemon == null) {
      daemon = new Daemon(filesystem, config, console);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      File parserRoot = daemon.getParser().getProjectRoot();
      if (!filesystem.getProjectRoot().equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            filesystem.getProjectRoot(), parserRoot));
      }

      // If Buck config has changed, invalidate the cache and create a new daemon.
      if (!daemon.getConfig().equals(config)) {
        daemon.close();
        daemon = new Daemon(filesystem, config, console);
      }
    }
    return daemon;
  }

  @VisibleForTesting
  public Main(PrintStream stdOut, PrintStream stdErr) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.platform = Platform.detect();
  }

  /** Prints the usage message to standard error. */
  @VisibleForTesting
  int usage() {
    stdErr.println("buck build tool");

    stdErr.println("usage:");
    stdErr.println("  buck [options]");
    stdErr.println("  buck command --help");
    stdErr.println("  buck command [command-options]");
    stdErr.println("available commands:");

    int lengthOfLongestCommand = 0;
    for (Command command : Command.values()) {
      String name = command.name();
      if (name.length() > lengthOfLongestCommand) {
        lengthOfLongestCommand = name.length();
      }
    }

    for (Command command : Command.values()) {
      String name = command.name().toLowerCase();
      stdErr.printf("  %s%s  %s\n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }

    stdErr.println("options:");
    new GenericBuckOptions(stdOut, stdErr).printUsage();
    return 1;
  }

  /**
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public int runMainWithExitCode(File projectRoot, String... args) throws Exception {
    if (args.length == 0) {
      return usage();
    }

    // Create common command parameters. projectFilesystem initialization looks odd because it needs
    // ignorePaths from a BuckConfig instance, which in turn needs a ProjectFilesystem (i.e. this
    // solves a bootstrapping issue).
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        projectRoot,
        createBuckConfig(new ProjectFilesystem(projectRoot), platform).getIgnorePaths());
    BuckConfig config = createBuckConfig(projectFilesystem, platform);
    Verbosity verbosity = VerbosityParser.parse(args);
    final Console console = new Console(verbosity, stdOut, stdErr, config.createAnsi());
    KnownBuildRuleTypes knownBuildRuleTypes = new KnownBuildRuleTypes();

    // Create or get and invalidate cached command parameters.
    Parser parser;
    if (isDaemon()) {
      Daemon daemon = getDaemon(projectFilesystem, config, console);
      daemon.watchFileSystem();
      parser = daemon.getParser();
    } else {
      parser = new Parser(projectFilesystem,
          knownBuildRuleTypes,
          console,
          config.getPythonInterpreter());
    }

    Clock clock = new DefaultClock();
    final BuckEventBus buildEventBus = new BuckEventBus(clock);

    // Find and execute command.
    Optional<Command> command = Command.getCommandForName(args[0], console);
    if (command.isPresent()) {
      ImmutableList<BuckEventListener> eventListeners =
          addEventListeners(buildEventBus,
              clock,
              projectFilesystem,
              console);
      String[] remainingArgs = new String[args.length - 1];
      System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);

      Command executingCommand = command.get();
      String commandName = executingCommand.name().toLowerCase();

      buildEventBus.post(CommandEvent.started(commandName, isDaemon()));

      // The ArtifactCache is constructed lazily so that we do not try to connect to Cassandra when
      // running commands such as `buck clean`.
      ArtifactCacheFactory artifactCacheFactory = new ArtifactCacheFactory() {
        @Override
        public ArtifactCache newInstance(AbstractCommandOptions options) {
          if (options.isNoCache()) {
            return new NoopArtifactCache();
          } else {
            buildEventBus.post(ArtifactCacheEvent.started(ArtifactCacheEvent.Operation.CONNECT));
            ArtifactCache artifactCache = new LoggingArtifactCacheDecorator(buildEventBus)
                .decorate(options.getBuckConfig().createArtifactCache(console));
            buildEventBus.post(ArtifactCacheEvent.finished(ArtifactCacheEvent.Operation.CONNECT,
                /* success */ true));
            return artifactCache;
          }
        }
      };

      int exitCode = executingCommand.execute(remainingArgs, config, new CommandRunnerParams(
          console,
          projectFilesystem,
          new KnownBuildRuleTypes(),
          artifactCacheFactory,
          buildEventBus,
          parser,
          platform));

      buildEventBus.post(CommandEvent.finished(commandName, isDaemon(), exitCode));

      ExecutorService buildEventBusExecutor = buildEventBus.getExecutorService();
      buildEventBusExecutor.shutdown();
      try {
        buildEventBusExecutor.awaitTermination(15, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // Give the eventBus 15 seconds to finish dispatching all events, but if they should fail
        // to finish in that amount of time just eat it, the end user doesn't care.
      }
      for (BuckEventListener eventListener : eventListeners) {
        eventListener.outputTrace();
      }
      return exitCode;
    } else {
      int exitCode = new GenericBuckOptions(stdOut, stdErr).execute(args);
      if (exitCode == GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE) {
        return usage();
      } else {
        return exitCode;
      }
    }
  }

  private ImmutableList<BuckEventListener> addEventListeners(BuckEventBus buckEvents,
                                                             Clock clock,
                                                             ProjectFilesystem projectFilesystem,
                                                             Console console) {
    ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment();

    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(new JavaUtilsLoggingBuildListener())
            .add(new ChromeTraceBuildListener(projectFilesystem));

    if (console.getAnsi().isAnsiTerminal()) {
      SuperConsoleEventBusListener superConsole =
          new SuperConsoleEventBusListener(console, clock, executionEnvironment);
      superConsole.startRenderScheduler(100, TimeUnit.MILLISECONDS);
      eventListenersBuilder.add(superConsole);
    } else {
      eventListenersBuilder.add(new SimpleConsoleEventBusListener(console, clock));
    }

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

    JavaUtilsLoggingBuildListener.ensureLogFileIsWritten();
    return eventListeners;
  }


  /**
   * @param projectFilesystem The directory that is the root of the project being built.
   */
  private static BuckConfig createBuckConfig(ProjectFilesystem projectFilesystem, Platform platform)
      throws IOException {
    ImmutableList.Builder<File> configFileBuilder = ImmutableList.builder();
    File configFile = projectFilesystem.getFileForRelativePath(DEFAULT_BUCK_CONFIG_FILE_NAME);
    if (configFile.isFile()) {
      configFileBuilder.add(configFile);
    }
    File overrideConfigFile = projectFilesystem.getFileForRelativePath(
        DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME);
    if (overrideConfigFile.isFile()) {
      configFileBuilder.add(overrideConfigFile);
    }

    ImmutableList<File> configFiles = configFileBuilder.build();
    return BuckConfig.createFromFiles(projectFilesystem, configFiles, platform);
  }

  @VisibleForTesting
  int tryRunMainWithExitCode(File projectRoot, String... args) throws Exception {
    // TODO(user): enforce write command exclusion, but allow concurrent read only commands?
    if (!commandSemaphore.tryAcquire()) {
      return BUSY_EXIT_CODE;
    }
    try {
      return runMainWithExitCode(projectRoot, args);
    } catch (HumanReadableException e) {
      Console console = new Console(Verbosity.STANDARD_INFORMATION,
          stdOut,
          stdErr,
          new Ansi(platform));
      console.printBuildFailure(e.getHumanReadableErrorMessage());
      return FAIL_EXIT_CODE;
    } finally {
      commandSemaphore.release();
    }
  }

  public static void main(String[] args) {
    Main main = new Main(System.out, System.err);
    File projectRoot = new File(".");
    int exitCode = FAIL_EXIT_CODE;
    try {
      exitCode = main.tryRunMainWithExitCode(projectRoot, args);
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode);
    }
  }
}
