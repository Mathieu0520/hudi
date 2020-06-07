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

package org.apache.hudi.table.action.commit;

import org.apache.hudi.client.TaskContextSupplier;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieCommitException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.HoodieMergeOnReadTable;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.WorkloadProfile;
import org.apache.hudi.table.WorkloadStat;
import org.apache.hudi.table.action.BaseActionExecutor;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public abstract class BaseCommitActionExecutor<T extends HoodieRecordPayload<T>, I, K, O, P>
    extends BaseActionExecutor<HoodieWriteMetadata<O>, T, I, K, O, P> {

  private static final Logger LOG = LogManager.getLogger(BaseCommitActionExecutor.class);

  private final WriteOperationType operationType;
  protected final TaskContextSupplier taskContextSupplier;

  public BaseCommitActionExecutor(HoodieEngineContext context, HoodieWriteConfig config,
                                  HoodieTable table, String instantTime, WriteOperationType operationType, TaskContextSupplier taskContextSupplier) {
    this(context, config, table, instantTime, operationType, taskContextSupplier,null);
  }

  public BaseCommitActionExecutor(HoodieEngineContext context, HoodieWriteConfig config,
                                  HoodieTable table, String instantTime, WriteOperationType operationType,TaskContextSupplier taskContextSupplier,
                                  I inputRecordsRDD) {
    super(context, config, table, instantTime);
    this.taskContextSupplier = taskContextSupplier;
    this.operationType = operationType;
  }

  public abstract HoodieWriteMetadata<O> execute(I inputRecordsRDD);

  /**
   * Save the workload profile in an intermediate file (here re-using commit files) This is useful when performing
   * rollback for MOR tables. Only updates are recorded in the workload profile metadata since updates to log blocks
   * are unknown across batches Inserts (which are new parquet files) are rolled back based on commit time. // TODO :
   * Create a new WorkloadProfile metadata file instead of using HoodieCommitMetadata
   */
  void saveWorkloadProfileMetadataToInflight(WorkloadProfile profile, String instantTime)
      throws HoodieCommitException {
    try {
      HoodieCommitMetadata metadata = new HoodieCommitMetadata();
      profile.getPartitionPaths().forEach(path -> {
        WorkloadStat partitionStat = profile.getWorkloadStat(path.toString());
        partitionStat.getUpdateLocationToCount().forEach((key, value) -> {
          HoodieWriteStat writeStat = new HoodieWriteStat();
          writeStat.setFileId(key);
          // TODO : Write baseCommitTime is possible here ?
          writeStat.setPrevCommit(value.getKey());
          writeStat.setNumUpdateWrites(value.getValue());
          metadata.addWriteStat(path.toString(), writeStat);
        });
      });
      metadata.setOperationType(operationType);

      HoodieActiveTimeline activeTimeline = table.getActiveTimeline();
      String commitActionType = table.getMetaClient().getCommitActionType();
      HoodieInstant requested = new HoodieInstant(State.REQUESTED, commitActionType, instantTime);
      activeTimeline.transitionRequestedToInflight(requested,
          Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)),
          config.shouldAllowMultiWriteOnSameInstant());
    } catch (IOException io) {
      throw new HoodieCommitException("Failed to commit " + instantTime + " unable to save inflight metadata ", io);
    }
  }

  protected abstract void updateIndexAndCommitIfNeeded(O writeStatusRDD, HoodieWriteMetadata<O> result) ;

  protected void commitOnAutoCommit(HoodieWriteMetadata result) {
    if (config.shouldAutoCommit()) {
      LOG.info("Auto commit enabled: Committing " + instantTime);
      commit(Option.empty(), result);
    } else {
      LOG.info("Auto commit disabled for " + instantTime);
    }
  }

  protected abstract void commit(Option<Map<String, String>> extraMetadata, HoodieWriteMetadata<O> result);

  /**
   * Finalize Write operation.
   *
   * @param instantTime Instant Time
   * @param stats       Hoodie Write Stat
   */
  protected void finalizeWrite(String instantTime, List<HoodieWriteStat> stats, HoodieWriteMetadata result) {
    try {
      Instant start = Instant.now();
      ((HoodieMergeOnReadTable)table).finalizeWrite(context, instantTime, stats);
      result.setFinalizeDuration(Duration.between(start, Instant.now()));
    } catch (HoodieIOException ioe) {
      throw new HoodieCommitException("Failed to complete commit " + instantTime + " due to finalize errors.", ioe);
    }
  }

  private void updateMetadataAndRollingStats(HoodieCommitMetadata metadata, List<HoodieWriteStat> writeStats) {
    // 1. Look up the previous compaction/commit and get the HoodieCommitMetadata from there.
    // 2. Now, first read the existing rolling stats and merge with the result of current metadata.

    // Need to do this on every commit (delta or commit) to support COW and MOR.
    for (HoodieWriteStat stat : writeStats) {
      String partitionPath = stat.getPartitionPath();
      // TODO: why is stat.getPartitionPath() null at times here.
      metadata.addWriteStat(partitionPath, stat);
    }
  }

  protected boolean isWorkloadProfileNeeded() {
    return true;
  }

  protected abstract Iterator<List<WriteStatus>> handleInsert(String idPfx,
                                                              Iterator<HoodieRecord<T>> recordItr) throws Exception;

  protected abstract Iterator<List<WriteStatus>> handleUpdate(String partitionPath, String fileId,
                                                              Iterator<HoodieRecord<T>> recordItr) throws IOException;
}
