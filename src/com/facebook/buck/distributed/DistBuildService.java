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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsRange;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.CASContainsRequest;
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.FetchBuildGraphRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.FetchRuleKeyLogsRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesResponse;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.PathInfo;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.SequencedBuildSlaveEvent;
import com.facebook.buck.distributed.thrift.SetBuckDotFilePathsRequest;
import com.facebook.buck.distributed.thrift.SetBuckVersionRequest;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.distributed.thrift.StoreBuildGraphRequest;
import com.facebook.buck.distributed.thrift.StoreLocalChangesRequest;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusRequest;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DistBuildService implements Closeable {
  private static final Logger LOG = Logger.get(DistBuildService.class);
  private static final ThriftProtocol PROTOCOL_FOR_CLIENT_ONLY_STRUCTS = ThriftProtocol.COMPACT;

  private final FrontendService service;

  public DistBuildService(FrontendService service) {
    this.service = service;
  }

  public MultiGetBuildSlaveRealTimeLogsResponse fetchSlaveLogLines(
      final StampedeId stampedeId, final List<LogLineBatchRequest> logLineRequests)
      throws IOException {

    MultiGetBuildSlaveRealTimeLogsRequest getLogLinesRequest =
        new MultiGetBuildSlaveRealTimeLogsRequest();
    getLogLinesRequest.setStampedeId(stampedeId);
    getLogLinesRequest.setBatches(logLineRequests);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_REAL_TIME_LOGS);
    request.setMultiGetBuildSlaveRealTimeLogsRequest(getLogLinesRequest);
    FrontendResponse response = makeRequestChecked(request);
    return response.getMultiGetBuildSlaveRealTimeLogsResponse();
  }

  public MultiGetBuildSlaveLogDirResponse fetchBuildSlaveLogDir(
      final StampedeId stampedeId, final List<RunId> runIds) throws IOException {

    MultiGetBuildSlaveLogDirRequest getBuildSlaveLogDirRequest =
        new MultiGetBuildSlaveLogDirRequest();
    getBuildSlaveLogDirRequest.setStampedeId(stampedeId);
    getBuildSlaveLogDirRequest.setRunIds(runIds);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_LOG_DIR);
    request.setMultiGetBuildSlaveLogDirRequest(getBuildSlaveLogDirRequest);
    FrontendResponse response = makeRequestChecked(request);
    Preconditions.checkState(response.isSetMultiGetBuildSlaveLogDirResponse());
    return response.getMultiGetBuildSlaveLogDirResponse();
  }

  public void uploadTargetGraph(
      final BuildJobState buildJobState,
      final StampedeId stampedeId,
      final DistBuildClientStatsTracker distBuildClientStats)
      throws IOException {
    distBuildClientStats.startUploadTargetGraphTimer();

    // Serialize and send the whole buildJobState
    StoreBuildGraphRequest storeBuildGraphRequest = new StoreBuildGraphRequest();
    storeBuildGraphRequest.setStampedeId(stampedeId);
    storeBuildGraphRequest.setBuildGraph(BuildJobStateSerializer.serialize(buildJobState));

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.STORE_BUILD_GRAPH);
    request.setStoreBuildGraphRequest(storeBuildGraphRequest);
    makeRequestChecked(request);
    distBuildClientStats.stopUploadTargetGraphTimer();
    // No response expected.
  }

  public ListenableFuture<Void> uploadMissingFilesAsync(
      final Map<Integer, ProjectFilesystem> localFilesystemsByCell,
      final List<BuildJobStateFileHashes> fileHashes,
      final DistBuildClientStatsTracker distBuildClientStats,
      final ListeningExecutorService executorService) {
    distBuildClientStats.startUploadMissingFilesTimer();
    List<PathInfo> requiredFiles = new ArrayList<>();
    for (BuildJobStateFileHashes filesystem : fileHashes) {
      if (!filesystem.isSetEntries()) {
        continue;
      }
      ProjectFilesystem cellFilesystem =
          Preconditions.checkNotNull(localFilesystemsByCell.get(filesystem.getCellIndex()));
      for (BuildJobStateFileHashEntry file : filesystem.entries) {
        if (file.isSetRootSymLink()) {
          LOG.info("File with path [%s] is a symlink. Skipping upload..", file.path.getPath());
          continue;
        } else if (file.isIsDirectory()) {
          LOG.info("Path [%s] is a directory. Skipping upload..", file.path.getPath());
          continue;
        } else if (file.isPathIsAbsolute()) {
          LOG.info("Path [%s] is absolute. Skipping upload..", file.path.getPath());
          continue;
        }

        if (!file.isSetHashCode()) {
          throw new RuntimeException(
              String.format("Missing content hash for path [%s].", file.path.getPath()));
        }

        PathInfo pathInfo = new PathInfo();
        pathInfo.setPath(cellFilesystem.resolve(file.getPath().getPath()).toString());
        pathInfo.setContentHash(file.getHashCode());
        requiredFiles.add(pathInfo);
      }
    }

    LOG.info(
        "%d files are required to be uploaded. Now checking which ones are already present...",
        requiredFiles.size());

    try {
      return Futures.transform(
          uploadMissingFilesAsync(requiredFiles, executorService),
          uploadCount -> {
            distBuildClientStats.setMissingFilesUploadedCount(uploadCount);
            distBuildClientStats.stopUploadMissingFilesTimer();
            return null;
          },
          executorService);
    } catch (IOException e) {
      throw new RuntimeException("Failed to upload missing source files.", e);
    }
  }

  /**
   * This function takes a list of files which we need to be present in the CAS, and uploads only
   * the missing files. So it makes 2 requests to the server: {@link CASContainsRequest} and {@link
   * StoreLocalChangesRequest}.
   *
   * @param absPathsAndHashes List of {@link PathInfo} objects with absolute paths and content SHA1
   *     of the files which need to be uploaded.
   * @param executorService Executor to enable concurrent file reads and upload request.
   * @return A Future containing the number of missing files which were uploaded to the CAS. This
   *     future completes when the upload finishes.
   * @throws IOException
   */
  private ListenableFuture<Integer> uploadMissingFilesAsync(
      final List<PathInfo> absPathsAndHashes, final ListeningExecutorService executorService)
      throws IOException {

    Map<String, PathInfo> sha1ToPathInfo = new HashMap<>();
    for (PathInfo file : absPathsAndHashes) {
      sha1ToPathInfo.put(file.getContentHash(), file);
    }

    List<String> contentHashes = ImmutableList.copyOf(sha1ToPathInfo.keySet());
    final CASContainsRequest containsReq = new CASContainsRequest();
    containsReq.setContentSha1s(contentHashes);
    ListenableFuture<FrontendResponse> responseFuture =
        executorService.submit(
            () ->
                makeRequestChecked(
                    new FrontendRequest()
                        .setType(FrontendRequestType.CAS_CONTAINS)
                        .setCasContainsRequest(containsReq)));

    ListenableFuture<List<FileInfo>> filesToBeUploaded =
        Futures.transformAsync(
            responseFuture,
            response -> {
              Preconditions.checkState(
                  response.getCasContainsResponse().exists.size() == contentHashes.size());
              List<Boolean> isPresent = response.getCasContainsResponse().exists;
              List<ListenableFuture<FileInfo>> missingFilesFutureList = new LinkedList<>();
              for (int i = 0; i < isPresent.size(); ++i) {
                if (isPresent.get(i)) {
                  continue;
                }

                final String contentHash = contentHashes.get(i);

                // TODO(shivanker): We should upload the missing files in batches, or it might OOM.
                missingFilesFutureList.add(
                    executorService.submit(
                        () -> {
                          FileInfo file = new FileInfo();
                          file.setContentHash(contentHash);
                          try {
                            file.setContent(
                                Files.readAllBytes(
                                    Paths.get(
                                        Preconditions.checkNotNull(sha1ToPathInfo.get(contentHash))
                                            .getPath())));
                          } catch (IOException e) {
                            throw new IOException(
                                String.format(
                                    "Failed to read file for uploading to server: [%s]",
                                    sha1ToPathInfo.get(contentHash).getPath()),
                                e);
                          }
                          return file;
                        }));
              }

              LOG.info(
                  "%d out of %d files already exist in the CAS. Uploading %d files..",
                  sha1ToPathInfo.size() - missingFilesFutureList.size(),
                  sha1ToPathInfo.size(),
                  missingFilesFutureList.size());

              return Futures.allAsList(missingFilesFutureList);
            },
            executorService);

    return Futures.transform(
        filesToBeUploaded,
        fileList -> {
          StoreLocalChangesRequest storeReq = new StoreLocalChangesRequest();
          storeReq.setFiles(fileList);
          try {
            makeRequestChecked(
                new FrontendRequest()
                    .setType(FrontendRequestType.STORE_LOCAL_CHANGES)
                    .setStoreLocalChangesRequest(storeReq));
            // No response expected.
          } catch (IOException e) {
            throw new HumanReadableException(
                e, "Failed to upload [%d] missing files.", fileList.size());
          }
          return fileList.size();
        },
        executorService);
  }

  public BuildJob createBuild(
      BuildMode buildMode, int numberOfMinions, String repository, String tenantId)
      throws IOException {
    Preconditions.checkArgument(
        buildMode == BuildMode.REMOTE_BUILD
            || buildMode == BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR,
        "BuildMode [%s=%d] is currently not supported.",
        buildMode.toString(),
        buildMode.ordinal());
    Preconditions.checkArgument(
        numberOfMinions > 0,
        "The number of minions must be greater than zero. Value [%d] found.",
        numberOfMinions);
    // Tell server to create the build and get the build id.
    CreateBuildRequest createBuildRequest = new CreateBuildRequest();
    createBuildRequest
        .setCreateTimestampMillis(System.currentTimeMillis())
        .setBuildMode(buildMode)
        .setNumberOfMinions(numberOfMinions);

    if (repository != null && repository.length() > 0) {
      createBuildRequest.setRepository(repository);
    }

    if (tenantId != null && tenantId.length() > 0) {
      createBuildRequest.setTenantId(tenantId);
    }

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createBuildRequest);
    FrontendResponse response = makeRequestChecked(request);

    return response.getCreateBuildResponse().getBuildJob();
  }

  public BuildJob startBuild(StampedeId id) throws IOException {
    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setStampedeId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuildRequest(startRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getStartBuildResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJob getCurrentBuildJobState(StampedeId id) throws IOException {
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setStampedeId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatusRequest(statusRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getBuildStatusResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJobState fetchBuildJobState(StampedeId stampedeId) throws IOException {
    FrontendRequest request = createFetchBuildGraphRequest(stampedeId);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchBuildGraphResponse());
    Preconditions.checkState(response.getFetchBuildGraphResponse().isSetBuildGraph());
    Preconditions.checkState(response.getFetchBuildGraphResponse().getBuildGraph().length > 0);

    return BuildJobStateSerializer.deserialize(
        response.getFetchBuildGraphResponse().getBuildGraph());
  }

  public static FrontendRequest createFetchBuildGraphRequest(StampedeId stampedeId) {
    FetchBuildGraphRequest fetchBuildGraphRequest = new FetchBuildGraphRequest();
    fetchBuildGraphRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_GRAPH);
    frontendRequest.setFetchBuildGraphRequest(fetchBuildGraphRequest);
    return frontendRequest;
  }

  public InputStream fetchSourceFile(String hashCode) throws IOException {
    FrontendRequest request = createFetchSourceFileRequest(hashCode);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchSourceFilesResponse());
    Preconditions.checkState(response.getFetchSourceFilesResponse().isSetFiles());
    FetchSourceFilesResponse fetchSourceFilesResponse = response.getFetchSourceFilesResponse();
    Preconditions.checkState(1 == fetchSourceFilesResponse.getFilesSize());
    FileInfo file = fetchSourceFilesResponse.getFiles().get(0);
    Preconditions.checkState(file.isSetContent());

    return new ByteArrayInputStream(file.getContent());
  }

  public static FrontendRequest createFetchSourceFileRequest(String fileHash) {
    FetchSourceFilesRequest fetchSourceFileRequest = new FetchSourceFilesRequest();
    fetchSourceFileRequest.setContentHashesIsSet(true);
    fetchSourceFileRequest.addToContentHashes(fileHash);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_SRC_FILES);
    frontendRequest.setFetchSourceFilesRequest(fetchSourceFileRequest);
    return frontendRequest;
  }

  public static FrontendRequest createFrontendBuildStatusRequest(StampedeId stampedeId) {
    BuildStatusRequest buildStatusRequest = new BuildStatusRequest();
    buildStatusRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.BUILD_STATUS);
    frontendRequest.setBuildStatusRequest(buildStatusRequest);
    return frontendRequest;
  }

  public void setBuckVersion(
      StampedeId id, BuckVersion buckVersion, DistBuildClientStatsTracker distBuildClientStats)
      throws IOException {
    distBuildClientStats.startSetBuckVersionTimer();
    SetBuckVersionRequest setBuckVersionRequest = new SetBuckVersionRequest();
    setBuckVersionRequest.setStampedeId(id);
    setBuckVersionRequest.setBuckVersion(buckVersion);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_BUCK_VERSION);
    request.setSetBuckVersionRequest(setBuckVersionRequest);
    makeRequestChecked(request);
    distBuildClientStats.stopSetBuckVersionTimer();
  }

  public void setBuckDotFiles(StampedeId id, List<PathInfo> dotFilesRelativePaths)
      throws IOException {
    SetBuckDotFilePathsRequest storeBuckDotFilesRequest = new SetBuckDotFilePathsRequest();
    storeBuckDotFilesRequest.setStampedeId(id);
    storeBuckDotFilesRequest.setDotFiles(dotFilesRelativePaths);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_DOTFILE_PATHS);
    request.setSetBuckDotFilePathsRequest(storeBuckDotFilesRequest);
    makeRequestChecked(request);
  }

  public ListenableFuture<Void> uploadBuckDotFilesAsync(
      final StampedeId id,
      final ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      DistBuildClientStatsTracker distBuildClientStats,
      ListeningExecutorService executorService)
      throws IOException {
    distBuildClientStats.startUploadBuckDotFilesTimer();
    ListenableFuture<List<Path>> pathsFuture =
        executorService.submit(
            () -> {
              List<Path> buckDotFilesExceptConfig = new ArrayList<>();
              for (Path path : filesystem.getDirectoryContents(filesystem.getRootPath())) {
                String fileName = path.getFileName().toString();
                if (!filesystem.isDirectory(path)
                    && !filesystem.isSymLink(path)
                    && fileName.startsWith(".")
                    && fileName.contains("buck")
                    && !fileName.startsWith(".buckconfig")) {
                  buckDotFilesExceptConfig.add(path);
                }
              }

              return buckDotFilesExceptConfig;
            });

    ListenableFuture<Void> setFilesFuture =
        Futures.transformAsync(
            pathsFuture,
            paths -> {
              List<PathInfo> relativePathEntries = new LinkedList<>();
              for (Path path : paths) {
                PathInfo pathInfoObject = new PathInfo();
                pathInfoObject.setPath(path.toString());
                pathInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
                relativePathEntries.add(pathInfoObject);
              }

              setBuckDotFiles(id, relativePathEntries);
              return Futures.immediateFuture(null);
            },
            executorService);

    ListenableFuture<?> uploadFilesFuture =
        Futures.transformAsync(
            pathsFuture,
            paths -> {
              List<PathInfo> absolutePathEntries = new LinkedList<>();
              for (Path path : paths) {
                PathInfo pathInfoObject = new PathInfo();
                pathInfoObject.setPath(path.toAbsolutePath().toString());
                pathInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
                absolutePathEntries.add(pathInfoObject);
              }

              return uploadMissingFilesAsync(absolutePathEntries, executorService);
            },
            executorService);

    ListenableFuture<Void> resultFuture =
        Futures.transform(
            Futures.allAsList(ImmutableList.of(setFilesFuture, uploadFilesFuture)), input -> null);

    resultFuture.addListener(
        () -> distBuildClientStats.stopUploadBuckDotFilesTimer(), executorService);

    return resultFuture;
  }

  public void uploadBuildSlaveConsoleEvents(
      StampedeId stampedeId, RunId runId, List<BuildSlaveConsoleEvent> events) throws IOException {
    AppendBuildSlaveEventsRequest request = new AppendBuildSlaveEventsRequest();
    request.setStampedeId(stampedeId);
    request.setRunId(runId);
    for (BuildSlaveConsoleEvent slaveEvent : events) {
      BuildSlaveEvent buildSlaveEvent = new BuildSlaveEvent();
      buildSlaveEvent.setEventType(BuildSlaveEventType.CONSOLE_EVENT);
      buildSlaveEvent.setStampedeId(stampedeId);
      buildSlaveEvent.setRunId(runId);
      buildSlaveEvent.setConsoleEvent(slaveEvent);
      request.addToEvents(
          ThriftUtil.serializeToByteBuffer(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, buildSlaveEvent));
    }

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.APPEND_BUILD_SLAVE_EVENTS);
    frontendRequest.setAppendBuildSlaveEventsRequest(request);
    makeRequestChecked(frontendRequest);
  }

  public void updateBuildSlaveStatus(StampedeId stampedeId, RunId runId, BuildSlaveStatus status)
      throws IOException {
    UpdateBuildSlaveStatusRequest request = new UpdateBuildSlaveStatusRequest();
    request.setStampedeId(stampedeId);
    request.setRunId(runId);
    request.setBuildSlaveStatus(ThriftUtil.serialize(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, status));

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.UPDATE_BUILD_SLAVE_STATUS);
    frontendRequest.setUpdateBuildSlaveStatusRequest(request);
    makeRequestChecked(frontendRequest);
  }

  public BuildSlaveEventsQuery createBuildSlaveEventsQuery(
      StampedeId stampedeId, RunId runId, int firstEventToBeFetched) {
    BuildSlaveEventsQuery query = new BuildSlaveEventsQuery();
    query.setStampedeId(stampedeId);
    query.setRunId(runId);
    query.setFirstEventNumber(firstEventToBeFetched);
    return query;
  }

  public List<Pair<Integer, BuildSlaveEvent>> multiGetBuildSlaveEvents(
      List<BuildSlaveEventsQuery> eventsQueries) throws IOException {
    MultiGetBuildSlaveEventsRequest request = new MultiGetBuildSlaveEventsRequest();
    request.setRequests(eventsQueries);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.MULTI_GET_BUILD_SLAVE_EVENTS);
    frontendRequest.setMultiGetBuildSlaveEventsRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetMultiGetBuildSlaveEventsResponse());
    Preconditions.checkState(response.getMultiGetBuildSlaveEventsResponse().isSetResponses());

    List<Pair<Integer, BuildSlaveEvent>> result = new LinkedList<>();
    for (BuildSlaveEventsRange eventsRange :
        response.getMultiGetBuildSlaveEventsResponse().getResponses()) {
      Preconditions.checkState(eventsRange.isSetSuccess());
      if (!eventsRange.isSuccess()) {
        LOG.error(
            String.format(
                "Error in BuildSlaveEventsRange received from MultiGetBuildSlaveEvents: [%s]",
                eventsRange.getErrorMessage()));
        continue;
      }

      Preconditions.checkState(eventsRange.isSetEvents());
      for (SequencedBuildSlaveEvent slaveEventWithSeqId : eventsRange.getEvents()) {
        BuildSlaveEvent event = new BuildSlaveEvent();
        ThriftUtil.deserialize(
            PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, slaveEventWithSeqId.getEvent(), event);
        result.add(new Pair<>(slaveEventWithSeqId.getEventNumber(), event));
      }
    }
    return result;
  }

  public Optional<BuildSlaveStatus> fetchBuildSlaveStatus(StampedeId stampedeId, RunId runId)
      throws IOException {
    FetchBuildSlaveStatusRequest request = new FetchBuildSlaveStatusRequest();
    request.setStampedeId(stampedeId);
    request.setRunId(runId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_SLAVE_STATUS);
    frontendRequest.setFetchBuildSlaveStatusRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetFetchBuildSlaveStatusResponse());
    if (!response.getFetchBuildSlaveStatusResponse().isSetBuildSlaveStatus()) {
      return Optional.empty();
    }

    BuildSlaveStatus status = new BuildSlaveStatus();
    ThriftUtil.deserialize(
        PROTOCOL_FOR_CLIENT_ONLY_STRUCTS,
        response.getFetchBuildSlaveStatusResponse().getBuildSlaveStatus(),
        status);
    return Optional.of(status);
  }

  public List<RuleKeyLogEntry> fetchRuleKeyLogs(Collection<String> ruleKeys) throws IOException {
    FetchRuleKeyLogsRequest request = new FetchRuleKeyLogsRequest();
    request.setRuleKeys(Lists.newArrayList(ruleKeys));

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_RULE_KEY_LOGS);
    frontendRequest.setFetchRuleKeyLogsRequest(request);

    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetFetchRuleKeyLogsResponse());
    Preconditions.checkState(response.getFetchRuleKeyLogsResponse().isSetRuleKeyLogs());

    return response.getFetchRuleKeyLogsResponse().getRuleKeyLogs();
  }

  @Override
  public void close() throws IOException {
    service.close();
  }

  private FrontendResponse makeRequestChecked(FrontendRequest request) throws IOException {
    FrontendResponse response = service.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(
          String.format(
              "Stampede request of type [%s] failed with error message [%s].",
              request.getType().toString(), response.getErrorMessage()));
    }
    Preconditions.checkState(request.isSetType());
    Preconditions.checkState(request.getType().equals(response.getType()));
    return response;
  }
}
