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
package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.thrift.ArtifactMetadata;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheStoreRequest;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * This is the Thrift protocol for the cache. The underlying channel is still HTTP but the payload
 * is Thrift. To learn a bit more about the protocol please look at ThriftArtifactCacheProtocol.
 */
public class ThriftArtifactCache extends AbstractNetworkCache {

  private static final Logger LOG = Logger.get(ThriftArtifactCache.class);

  public static final MediaType HYBRID_THRIFT_STREAM_CONTENT_TYPE =
      MediaType.parse("application/x-hybrid-thrift-binary");
  public static final String PROTOCOL_HEADER = "X-Thrift-Protocol";
  public static final ThriftProtocol PROTOCOL = ThriftProtocol.COMPACT;

  private final String hybridThriftEndpoint;
  private final boolean distributedBuildModeEnabled;

  public ThriftArtifactCache(NetworkCacheArgs args) {
    super(args);
    Preconditions.checkArgument(
        args.getThriftEndpointPath().isPresent(),
        "Hybrid thrift endpoint path is mandatory for the ThriftArtifactCache.");
    this.hybridThriftEndpoint = args.getThriftEndpointPath().orElse("");
    this.distributedBuildModeEnabled = args.distributedBuildModeEnabled();
  }

  @Override
  protected FetchResult fetchImpl(RuleKey ruleKey, LazyPath output) throws IOException {
    FetchResult.Builder resultBuilder = FetchResult.builder();

    BuckCacheFetchRequest fetchRequest = new BuckCacheFetchRequest();
    com.facebook.buck.artifact_cache.thrift.RuleKey thriftRuleKey =
        new com.facebook.buck.artifact_cache.thrift.RuleKey();
    thriftRuleKey.setHashString(ruleKey.getHashCode().toString());
    fetchRequest.setRuleKey(thriftRuleKey);
    fetchRequest.setRepository(getRepository());
    fetchRequest.setScheduleType(scheduleType);
    fetchRequest.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    BuckCacheRequest cacheRequest = new BuckCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.FETCH);
    cacheRequest.setFetchRequest(fetchRequest);

    LOG.verbose("Will fetch key %s", thriftRuleKey);

    final ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest);
    Request.Builder builder = toOkHttpRequest(request);
    try (HttpResponse httpResponse = fetchClient.makeRequest(hybridThriftEndpoint, builder)) {
      if (httpResponse.statusCode() != 200) {
        String message =
            String.format(
                "Failed to fetch cache artifact with HTTP status code [%d:%s] "
                    + " to url [%s] for rule key [%s].",
                httpResponse.statusCode(),
                httpResponse.statusMessage(),
                httpResponse.requestUrl(),
                ruleKey.toString());
        LOG.error(message);
        return resultBuilder
            .setCacheResult(CacheResult.error(getName(), getMode(), message))
            .build();
      }

      try (ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
        resultBuilder.setResponseSizeBytes(httpResponse.contentLength());

        BuckCacheResponse cacheResponse = response.getThriftData();
        if (!cacheResponse.isWasSuccessful()) {
          LOG.warn("Request was unsuccessful: %s", cacheResponse.getErrorMessage());
          return resultBuilder
              .setCacheResult(
                  CacheResult.error(getName(), getMode(), cacheResponse.getErrorMessage()))
              .build();
        }

        BuckCacheFetchResponse fetchResponse = cacheResponse.getFetchResponse();

        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "Debug info for cache fetch request: request=[%s] response=[%s]",
              ThriftUtil.thriftToDebugJson(cacheRequest),
              ThriftUtil.thriftToDebugJson(cacheResponse));
        }

        if (!fetchResponse.isArtifactExists()) {
          LOG.verbose("Artifact did not exist.");
          return resultBuilder.setCacheResult(CacheResult.miss()).build();
        }

        LOG.verbose("Got artifact.  Attempting to read payload.");
        Path tmp = createTempFileForDownload();
        ThriftArtifactCacheProtocol.Response.ReadPayloadInfo readResult;
        try (OutputStream tmpFile = getProjectFilesystem().newFileOutputStream(tmp)) {
          try {
            readResult = response.readPayload(tmpFile);
          } catch (IOException e) {
            LOG.debug(e, "encountered an exception while receiving the payload for %s", ruleKey);
            throw e;
          }
          LOG.verbose("Successfully read payload: %d bytes.", readResult.getBytesRead());
        }

        if (!fetchResponse.isSetMetadata()) {
          String msg =
              String.format(
                  "ArtifactMetadata section is missing in the response. response=[%s]",
                  ThriftUtil.thriftToDebugJson(fetchResponse));
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }
        ArtifactMetadata metadata = fetchResponse.getMetadata();
        if (LOG.isVerboseEnabled()) {
          LOG.verbose(
              String.format(
                  "Fetched artifact with rule key [%s] contains the following metadata: [%s].",
                  ruleKey, ThriftUtil.thriftToDebugJson(metadata)));
        }

        if (!metadata.isSetRuleKeys()) {
          return resultBuilder
              .setCacheResult(
                  CacheResult.error(
                      getName(), getMode(), "Rule key section in the metadata is not set."))
              .build();
        }
        ImmutableSet<RuleKey> associatedRuleKeys = null;
        try {
          associatedRuleKeys = toImmutableSet(metadata.getRuleKeys());
        } catch (IllegalArgumentException e) {
          String msg =
              String.format(
                  "Exception parsing the rule keys in the metadata section [%s] with exception [%s].",
                  ThriftUtil.thriftToDebugJson(metadata), e.toString());
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }

        resultBuilder
            .setBuildTarget(Optional.ofNullable(metadata.getBuildTarget()))
            .setAssociatedRuleKeys(associatedRuleKeys)
            .setArtifactSizeBytes(readResult.getBytesRead());
        if (!metadata.isSetArtifactPayloadMd5()) {
          String msg = "Fetched artifact is missing the MD5 hash.";
          LOG.warn(msg);
        } else {
          resultBuilder.setArtifactContentHash(metadata.getArtifactPayloadMd5());
          if (!readResult
              .getMd5Hash()
              .equals(fetchResponse.getMetadata().getArtifactPayloadMd5())) {
            String msg =
                String.format(
                    "The artifact fetched from cache is corrupted. ExpectedMD5=[%s] ActualMD5=[%s]",
                    fetchResponse.getMetadata().getArtifactPayloadMd5(), readResult.getMd5Hash());
            LOG.error(msg);
            return resultBuilder
                .setCacheResult(CacheResult.error(getName(), getMode(), msg))
                .build();
          }
        }

        // This makes sure we don't have 'half downloaded files' in the dir cache.
        getProjectFilesystem().move(tmp, output.get(), StandardCopyOption.REPLACE_EXISTING);
        return resultBuilder
            .setCacheResult(
                CacheResult.hit(
                    getName(),
                    getMode(),
                    ImmutableMap.copyOf(fetchResponse.getMetadata().getMetadata()),
                    readResult.getBytesRead()))
            .build();
      }
    }
  }

  private static ImmutableSet<RuleKey> toImmutableSet(
      List<com.facebook.buck.artifact_cache.thrift.RuleKey> ruleKeys) {
    return ImmutableSet.copyOf(
        Iterables.transform(ruleKeys, input -> new RuleKey(input.getHashString())));
  }

  @Override
  protected StoreResult storeImpl(final ArtifactInfo info, final Path file) throws IOException {
    StoreResult.Builder resultBuilder = StoreResult.builder();
    final ByteSource artifact =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return getProjectFilesystem().newFileInputStream(file);
          }
        };

    BuckCacheStoreRequest storeRequest = new BuckCacheStoreRequest();
    ArtifactMetadata artifactMetadata =
        infoToMetadata(info, artifact, getRepository(), scheduleType, distributedBuildModeEnabled);
    storeRequest.setMetadata(artifactMetadata);
    PayloadInfo payloadInfo = new PayloadInfo();
    long artifactSizeBytes = artifact.size();
    payloadInfo.setSizeBytes(artifactSizeBytes);
    BuckCacheRequest cacheRequest = new BuckCacheRequest();
    cacheRequest.addToPayloads(payloadInfo);
    cacheRequest.setType(BuckCacheRequestType.STORE);
    cacheRequest.setStoreRequest(storeRequest);

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          String.format(
              "Storing artifact with metadata: [%s].",
              ThriftUtil.thriftToDebugJson(artifactMetadata)));
    }

    final ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest, artifact);
    Request.Builder builder = toOkHttpRequest(request);
    resultBuilder.setRequestSizeBytes(request.getRequestLengthBytes());
    try (HttpResponse httpResponse = storeClient.makeRequest(hybridThriftEndpoint, builder)) {
      if (httpResponse.statusCode() != 200) {
        throw new IOException(
            String.format(
                "Failed to store cache artifact with HTTP status code [%d:%s] "
                    + " to url [%s] for build target [%s] that has size [%d] bytes.",
                httpResponse.statusCode(),
                httpResponse.statusMessage(),
                httpResponse.requestUrl(),
                info.getBuildTarget().orElse(null),
                artifactSizeBytes));
      }

      try (ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
        BuckCacheResponse cacheResponse = response.getThriftData();
        if (!cacheResponse.isWasSuccessful()) {
          reportFailureWithFormatKey(
              "Failed to store artifact with thriftErrorMessage=[%s] "
                  + "url=[%s] artifactSizeBytes=[%d]",
              response.getThriftData().getErrorMessage(),
              httpResponse.requestUrl(),
              artifactSizeBytes);
        }

        resultBuilder.setArtifactContentHash(storeRequest.getMetadata().artifactPayloadMd5);
        resultBuilder.setWasStoreSuccessful(cacheResponse.isWasSuccessful());

        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "Debug info for cache store request: artifactMetadata=[%s] response=[%s]",
              ThriftUtil.thriftToDebugJson(artifactMetadata),
              ThriftUtil.thriftToDebugJson(cacheResponse));
        }
      }
    }
    return resultBuilder.build();
  }

  private Path createTempFileForDownload() throws IOException {
    getProjectFilesystem().mkdirs(getProjectFilesystem().getBuckPaths().getScratchDir());
    return getProjectFilesystem()
        .createTempFile(
            getProjectFilesystem().getBuckPaths().getScratchDir(), "buckcache_artifact", ".tmp");
  }

  private static ArtifactMetadata infoToMetadata(
      ArtifactInfo info,
      ByteSource file,
      String repository,
      String scheduleType,
      boolean distributedBuildModeEnabled)
      throws IOException {
    ArtifactMetadata metadata = new ArtifactMetadata();
    if (info.getBuildTarget().isPresent()) {
      metadata.setBuildTarget(info.getBuildTarget().get().toString());
    }

    metadata.setRuleKeys(
        ImmutableList.copyOf(
            Iterables.transform(
                info.getRuleKeys(),
                input -> {
                  com.facebook.buck.artifact_cache.thrift.RuleKey ruleKey =
                      new com.facebook.buck.artifact_cache.thrift.RuleKey();
                  ruleKey.setHashString(input.getHashCode().toString());
                  return ruleKey;
                })));

    metadata.setMetadata(info.getMetadata());
    metadata.setArtifactPayloadMd5(ThriftArtifactCacheProtocol.computeMd5Hash(file));
    metadata.setRepository(repository);
    metadata.setScheduleType(scheduleType);
    metadata.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    return metadata;
  }

  private static Request.Builder toOkHttpRequest(
      final ThriftArtifactCacheProtocol.Request request) {
    Request.Builder builder =
        new Request.Builder().addHeader(PROTOCOL_HEADER, PROTOCOL.toString().toLowerCase());
    builder.post(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return HYBRID_THRIFT_STREAM_CONTENT_TYPE;
          }

          @Override
          public long contentLength() throws IOException {
            return request.getRequestLengthBytes();
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            request.writeAndClose(bufferedSink.outputStream());
          }
        });

    return builder;
  }
}
