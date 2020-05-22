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

package org.apache.hudi.client;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hudi.client.utils.ClientUtils;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.context.HoodieEngineContext;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.Serializable;

/**
 * Abstract class taking care of holding common member variables (FileSystem, SparkContext, HoodieConfigs) Also, manages
 * embedded timeline-server if enabled.
 */
public abstract class AbstractHoodieClient implements Serializable, AutoCloseable {

  private static final Logger LOG = LogManager.getLogger(AbstractHoodieClient.class);

  protected final transient FileSystem fs;
  protected final transient HoodieEngineContext context;
  protected final HoodieWriteConfig config;
  protected final String basePath;

  protected AbstractHoodieClient(HoodieEngineContext context, HoodieWriteConfig clientConfig) {
    this.fs = FSUtils.getFs(clientConfig.getBasePath(), context.getHadoopConf().get());
    this.context = context;
    this.basePath = clientConfig.getBasePath();
    this.config = clientConfig;
  }

  /**
   * Releases any resources used by the client.
   */
  @Override
  public void close() {
  }



  public HoodieWriteConfig getConfig() {
    return config;
  }

  protected HoodieTableMetaClient createMetaClient(boolean loadActiveTimelineOnLoad) {
    return ClientUtils.createMetaClient(context, config, loadActiveTimelineOnLoad);
  }
}
