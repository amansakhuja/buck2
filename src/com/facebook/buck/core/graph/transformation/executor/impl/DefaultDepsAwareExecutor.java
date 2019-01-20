/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.graph.transformation.executor.impl;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 */
public class DefaultDepsAwareExecutor<T> extends AbstractDefaultDepsAwareExecutor<T> {

  private DefaultDepsAwareExecutor(
      Future<?>[] workers, LinkedBlockingDeque<DefaultDepsAwareTask<T>> workQueue) {
    super(workQueue, workers);
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutor} from the given {@link ForkJoinPool}. The executor
   * will have the same parallelism as the backing {@link ForkJoinPool}.
   */
  public static <U> DefaultDepsAwareExecutor<U> from(ForkJoinPool executorService) {
    return from(executorService, executorService.getParallelism());
  }

  /**
   * Creates a {@link DefaultDepsAwareExecutor} from the given {@link ExecutorService}, maintaining
   * up to the specified parallelism. It is up to the user to ensure that the backing {@link
   * ExecutorService} can support the specified parallelism.
   */
  public static <U> DefaultDepsAwareExecutor<U> from(
      ExecutorService executorService, int parallelism) {

    LinkedBlockingDeque<DefaultDepsAwareTask<U>> workQueue = new LinkedBlockingDeque<>();

    Future<?>[] workers =
        startWorkers(executorService, parallelism, workQueue, DefaultDepsAwareWorker::new);
    DefaultDepsAwareExecutor<U> executor = new DefaultDepsAwareExecutor<>(workers, workQueue);

    return executor;
  }
}
