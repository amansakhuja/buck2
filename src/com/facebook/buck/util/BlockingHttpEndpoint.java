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
package com.facebook.buck.util;

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * HttpEndpoint implementation which only allows a certain number of concurrent requests to be in
 * flight at any given point in time.
 */
public class BlockingHttpEndpoint implements HttpEndpoint {

  public static final int DEFAULT_COMMON_TIMEOUT_MS = 5000;

  private URL url;
  private int timeout = DEFAULT_COMMON_TIMEOUT_MS;
  private final ListeningExecutorService requestService;

  public BlockingHttpEndpoint(String url, int maxParallelRequests) throws MalformedURLException {
    this.url = new URL(url);

    // Create an ExecutorService that blocks after N requests are in flight.  Taken from
    // http://www.springone2gx.com/blog/billy_newport/2011/05/there_s_more_to_configuring_threadpools_than_thread_pool_size
    LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(maxParallelRequests);
    ExecutorService executor = new ThreadPoolExecutor(maxParallelRequests,
        maxParallelRequests,
        2L,
        TimeUnit.MINUTES,
        workQueue,
        new ThreadPoolExecutor.CallerRunsPolicy());
    requestService = MoreExecutors.listeningDecorator(executor);
  }

  @Override
  public ListenableFuture<?> post(String content) throws IOException {
    HttpURLConnection connection = buildConnection("POST");
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    return send(connection, content);
  }

  private ListenableFuture<?> send(final HttpURLConnection connection, final String content) {
    return requestService.submit(new Runnable() {
      @Override
      public void run() {
        try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
          out.writeBytes(content);
          out.flush();
          out.close();
          try (InputStream response = connection.getInputStream()) {
            ByteStreams.copy(response, ByteStreams.nullOutputStream());
          }
        }  catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  private HttpURLConnection buildConnection(String httpMethod) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
    connection.setUseCaches(false);
    connection.setDoOutput(true);
    connection.setConnectTimeout(timeout);
    connection.setReadTimeout(timeout);
    connection.setRequestMethod(httpMethod);
    return connection;
  }
}
