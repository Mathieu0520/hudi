/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.client.embebbed;

import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.table.view.FileSystemViewManager;
import org.apache.hudi.common.table.view.FileSystemViewStorageConfig;
import org.apache.hudi.common.table.view.FileSystemViewStorageType;
import org.apache.hudi.common.util.NetworkUtils;
import org.apache.hudi.timeline.service.TimelineService;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;

/**
 * Timeline Service that runs as part of write client.
 */
public abstract class BaseEmbeddedTimelineService {

  private static final Logger LOG = LogManager.getLogger(BaseEmbeddedTimelineService.class);

  private int serverPort;
  protected String hostAddr;
  private final SerializableConfiguration hadoopConf;
  private final FileSystemViewStorageConfig config;
  private transient FileSystemViewManager viewManager;
  private transient TimelineService server;

  public BaseEmbeddedTimelineService(HoodieEngineContext context, FileSystemViewStorageConfig config) {
    setHostAddrFromContext(context);
    if (hostAddr == null) {
      this.hostAddr = NetworkUtils.getHostname();
    }
    this.config = config;
    this.hadoopConf = context.getHadoopConf();
    this.viewManager = createViewManager();
  }

  private FileSystemViewManager createViewManager() {
    // Using passed-in configs to build view storage configs
    FileSystemViewStorageConfig.Builder builder =
        FileSystemViewStorageConfig.newBuilder().fromProperties(config.getProps());
    FileSystemViewStorageType storageType = builder.build().getStorageType();
    if (storageType.equals(FileSystemViewStorageType.REMOTE_ONLY)
        || storageType.equals(FileSystemViewStorageType.REMOTE_FIRST)) {
      // Reset to default if set to Remote
      builder.withStorageType(FileSystemViewStorageType.MEMORY);
    }
    return FileSystemViewManager.createViewManager(hadoopConf, builder.build());
  }

  public void startServer() throws IOException {
    server = new TimelineService(0, viewManager, hadoopConf.newCopy());
    serverPort = server.startService();
    LOG.info("Started embedded timeline server at " + hostAddr + ":" + serverPort);
  }

  public abstract void setHostAddrFromContext(HoodieEngineContext context);

  /**
   * Retrieves proper view storage configs for remote clients to access this service.
   */
  public FileSystemViewStorageConfig getRemoteFileSystemViewConfig() {
    FileSystemViewStorageType viewStorageType = config.shouldEnableBackupForRemoteFileSystemView()
            ? FileSystemViewStorageType.REMOTE_FIRST : FileSystemViewStorageType.REMOTE_ONLY;
    return FileSystemViewStorageConfig.newBuilder().withStorageType(viewStorageType)
        .withRemoteServerHost(hostAddr).withRemoteServerPort(serverPort).build();
  }

  public FileSystemViewManager getViewManager() {
    return viewManager;
  }

  public void stop() {
    if (null != server) {
      LOG.info("Closing Timeline server");
      this.server.close();
      this.server = null;
      this.viewManager = null;
      LOG.info("Closed Timeline server");
    }
  }
}