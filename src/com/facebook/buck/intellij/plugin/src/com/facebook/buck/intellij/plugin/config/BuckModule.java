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

package com.facebook.buck.intellij.plugin.config;

import com.facebook.buck.intellij.plugin.ui.BuckEventsConsumer;
import com.facebook.buck.intellij.plugin.ui.BuckToolWindowFactory;
import com.facebook.buck.intellij.plugin.ui.BuckUIManager;
import com.facebook.buck.intellij.plugin.ws.BuckClient;
import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventHandler;
import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventsQueue;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckEventsConsumerFactory;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;

public final class BuckModule implements ProjectComponent {

    private Project mProject;
    private BuckClient mClient = new BuckClient();
    private BuckEventHandler mEventHandler;
    private BuckEventsConsumer mBu;

    public BuckModule(final Project project) {
        mProject = project;

        BuckEventsConsumerFactory consumerFactory = new BuckEventsConsumerFactory(project);
        BuckEventsQueue queue = new BuckEventsQueue(consumerFactory);

        mEventHandler = new BuckEventHandler(
            queue,
            new Runnable() {
                @Override
                public void run() {
                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                        BuckToolWindowFactory.outputConsoleMessage(
                            project,
                            "Connected to buck!\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        );
                        }
                    });
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                        BuckToolWindowFactory.outputConsoleMessage(
                            project,
                            "Disconnected from buck!\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        );
                        }
                    });
                    BuckModule mod = project.getComponent(BuckModule.class);
                    mod.disconnect("Received disconnect from the server");
                }
            }
        );
    }

    @Override
    public String getComponentName() {
        return "buck.connector";
    }

    @Override
    public void initComponent() {}

    @Override
    public void disposeComponent() {}

    @Override
    public void projectOpened() {
        connect();
    }

    @Override
    public void projectClosed() {
        disconnect();
    }

    public boolean isConnected() {
        return mClient.isConnected();
    }

    public void disconnect() {
        if (mClient.isConnected()) {
            if (mBu != null) {
                mBu.detach();
            }
            mClient.disconnect();
        }
    }

    public void disconnect(String message) {
        if (mClient.isConnected()) {
            if (mBu != null) {
                mBu.detachWithMessage(message);
            }
            mClient.disconnect();
        }
    }

    public void connect() {
        if (!mClient.isConnected()) {
            BuckWSServerPortUtils wsPortUtils = new BuckWSServerPortUtils();
            int port = wsPortUtils.getPort(this.mProject.getBasePath());
            if (port != -1) {
                mClient = new BuckClient(port, mEventHandler);
                // Initiate connecting
                this.mClient.connect();
            }
        }
    }

    public void attach(BuckEventsConsumer bu, String target) {
        if (mBu != null) {
            mBu.detach();
        }
        mBu = bu;
        mBu.attach(target, BuckUIManager.getInstance(mProject).getTreeModel());
    }
}
