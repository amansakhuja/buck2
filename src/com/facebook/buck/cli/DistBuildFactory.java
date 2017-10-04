/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildExecutorArgs;
import com.facebook.buck.distributed.DistBuildLogStateTracker;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.FileContentsProvider;
import com.facebook.buck.distributed.FileMaterializationStatsTracker;
import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.MultiSourceContentsProvider;
import com.facebook.buck.distributed.ServerContentsProvider;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.OkHttpClient;

public abstract class DistBuildFactory {
  private DistBuildFactory() {
    // Do not instantiate.
  }

  public static DistBuildService newDistBuildService(CommandRunnerParams params) {
    return new DistBuildService(newFrontendService(params));
  }

  public static DistBuildLogStateTracker newDistBuildLogStateTracker(
      Path logDir, ProjectFilesystem fileSystem) {
    return new DistBuildLogStateTracker(logDir, fileSystem);
  }

  public static FrontendService newFrontendService(CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());
    ClientSideSlb slb =
        config.getFrontendConfig().createClientSideSlb(params.getClock(), params.getBuckEventBus());
    OkHttpClient client = config.createOkHttpClient();

    return new FrontendService(
        ThriftOverHttpServiceConfig.of(
            new LoadBalancedService(slb, client, params.getBuckEventBus())));
  }

  public static FileContentsProvider createMultiSourceContentsProvider(
      DistBuildService service,
      DistBuildConfig distBuildConfig,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      ScheduledExecutorService sourceFileMultiFetchScheduler,
      ListeningExecutorService executorService,
      ProjectFilesystemFactory projectFilesystemFactory,
      Optional<Path> globalCacheDir)
      throws IOException, InterruptedException {
    return new MultiSourceContentsProvider(
        new ServerContentsProvider(
            service,
            sourceFileMultiFetchScheduler,
            executorService,
            fileMaterializationStatsTracker,
            distBuildConfig.getSourceFileMultiFetchBufferPeriodMs(),
            distBuildConfig.getSourceFileMultiFetchMaxBufferSize()),
        fileMaterializationStatsTracker,
        executorService,
        projectFilesystemFactory,
        globalCacheDir);
  }

  public static DistBuildSlaveExecutor createDistBuildExecutor(
      DistBuildState state,
      CommandRunnerParams params,
      WeightedListeningExecutorService executorService,
      DistBuildService service,
      DistBuildMode mode,
      int coordinatorPort,
      String coordinatorAddress,
      Optional<StampedeId> stampedeId,
      FileContentsProvider fileContentsProvider,
      DistBuildConfig distBuildConfig) {
    Preconditions.checkArgument(state.getCells().size() > 0);

    // Create a cache factory which uses a combination of the distributed build config,
    // overridden with the local buck config (i.e. the build slave).
    ArtifactCacheFactory distBuildArtifactCacheFactory =
        params.getArtifactCacheFactory().cloneWith(state.getRootCell().getBuckConfig());

    DistBuildSlaveExecutor executor =
        new DistBuildSlaveExecutor(
            DistBuildExecutorArgs.builder()
                .setBuckEventBus(params.getBuckEventBus())
                .setPlatform(params.getPlatform())
                .setClock(params.getClock())
                .setArtifactCache(distBuildArtifactCacheFactory.newInstance(true))
                .setState(state)
                .setParser(params.getParser())
                .setExecutorService(executorService)
                .setActionGraphCache(params.getActionGraphCache())
                //TODO(alisdair,shivanker): Change this to state.getRootCell().getBuckConfig().getKeySeed()
                .setCacheKeySeed(params.getBuckConfig().getKeySeed())
                .setConsole(params.getConsole())
                .setProvider(fileContentsProvider)
                .setExecutors(params.getExecutors())
                .setDistBuildMode(mode)
                .setRemoteCoordinatorPort(coordinatorPort)
                .setRemoteCoordinatorAddress(coordinatorAddress)
                .setStampedeId(stampedeId.orElse(new StampedeId().setId("LOCAL_FILE")))
                .setVersionedTargetGraphCache(params.getVersionedTargetGraphCache())
                .setBuildInfoStoreManager(params.getBuildInfoStoreManager())
                .setDistBuildService(service)
                .setDistBuildConfig(distBuildConfig)
                .setProjectFilesystemFactory(params.getProjectFilesystemFactory())
                .build());
    return executor;
  }
}
