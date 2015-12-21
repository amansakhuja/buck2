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

package com.facebook.buck.intellij.plugin.ws.buckevents;

import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.google.common.collect.EvictingQueue;
import com.intellij.openapi.application.ApplicationManager;

public class BuckEventsQueue implements BuckEventsQueueInterface {

    private BuckEventsConsumerFactory mFactory;
    private EvictingQueue<BuckEventInterface> mLowPri;

    private static int maxLowPriSize = 50;
    private static int lowPriBatchSize = 10;

    public BuckEventsQueue(BuckEventsConsumerFactory factory) {
        mFactory = factory;
        mLowPri = EvictingQueue.create(maxLowPriSize);
    }

    @Override
    public void add(BuckEventInterface event) {
        offer(event);
    }

    private void offer(BuckEventInterface event) {
        if (event.getPriority() == BuckEventBase.PRIORITY_HIGH ||
                event.getPriority() == BuckEventBase.PRIORITY_MED) {
            handleHighPriEvent(event);
            return;
        }
        // Add for later, batch processing
        mLowPri.add(event);
        if (mLowPri.size() > lowPriBatchSize) {
            handleLowPriBatch();
        }
    }

    private void handleHighPriEvent(final BuckEventInterface event) {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                synchronized (BuckEventsQueue.this.mFactory) {
                    event.handleEvent(BuckEventsQueue.this.mFactory);
                }
            }
        });
    }

    private void handleLowPriBatch() {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                BuckEventsQueue.this.handleLowPriBatchSameThread();
            }
        });
    }

    private void handleLowPriBatchSameThread() {
        synchronized (mFactory) {
            mFactory.getBatchStartConsumer().startBatch();

            while (mLowPri.peek() != null) {
                BuckEventInterface currentEvent = mLowPri.poll();
                currentEvent.handleEvent(mFactory);
            }

            mFactory.getBatchCommitConsumer().commitBatch();
        }
    }
}
