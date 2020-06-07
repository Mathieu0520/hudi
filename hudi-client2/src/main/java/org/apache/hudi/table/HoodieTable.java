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

package org.apache.hudi.table;

import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieCompactionPlan;
import org.apache.hudi.avro.model.HoodieRestoreMetadata;
import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.avro.model.HoodieSavepointMetadata;
import org.apache.hudi.client.TaskContextSupplier;
import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.fs.ConsistencyGuard;
import org.apache.hudi.common.fs.ConsistencyGuard.FileVisibility;
import org.apache.hudi.common.fs.FailSafeConsistencyGuard;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.view.FileSystemViewManager;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.table.view.SyncableFileSystemView;
import org.apache.hudi.common.table.view.TableFileSystemView;
import org.apache.hudi.common.table.view.TableFileSystemView.BaseFileOnlyView;
import org.apache.hudi.common.table.view.TableFileSystemView.SliceView;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieInsertException;
import org.apache.hudi.exception.HoodieUpsertException;
import org.apache.hudi.index.AbstractHoodieIndex;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract implementation of a HoodieTable.
 */
public abstract class HoodieTable<T extends HoodieRecordPayload<T>, I, K, O, P> implements Serializable {

  private static final Logger LOG = LogManager.getLogger(HoodieTable.class);

  protected final HoodieWriteConfig config;
  protected final HoodieTableMetaClient metaClient;
  protected final AbstractHoodieIndex<T, I, K, O, P> index;

  private SerializableConfiguration hadoopConfiguration;
  private transient FileSystemViewManager viewManager;

  protected final TaskContextSupplier taskContextSupplier;

  protected HoodieTable(HoodieWriteConfig config, Configuration hadoopConf, HoodieTableMetaClient metaClient,
                        AbstractHoodieIndex<T, I, K, O, P> inedx, TaskContextSupplier taskContextSupplier) {
    this.config = config;
    this.hadoopConfiguration = new SerializableConfiguration(hadoopConf);
    this.viewManager = FileSystemViewManager.createViewManager(new SerializableConfiguration(hadoopConf),
        config.getViewStorageConfig());
    this.metaClient = metaClient;
    this.index = inedx;
    this.taskContextSupplier = taskContextSupplier;
  }

  private synchronized FileSystemViewManager getViewManager() {
    if (null == viewManager) {
      viewManager = FileSystemViewManager.createViewManager(hadoopConfiguration, config.getViewStorageConfig());
    }
    return viewManager;
  }

  /**
   * Upsert a batch of new records into Hoodie table at the supplied instantTime.
   *
   * @param context         Java Spark Context context
   * @param instantTime Instant Time for the action
   * @param records     JavaRDD of hoodieRecords to upsert
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata upsert(HoodieEngineContext context, String instantTime,
                                             I records);

  /**
   * Insert a batch of new records into Hoodie table at the supplied instantTime.
   *
   * @param context         Java Spark Context context
   * @param instantTime Instant Time for the action
   * @param records     JavaRDD of hoodieRecords to upsert
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata insert(HoodieEngineContext context, String instantTime,
                                             I records);

  /**
   * Bulk Insert a batch of new records into Hoodie table at the supplied instantTime.
   *
   * @param context                   Java Spark Context context
   * @param instantTime           Instant Time for the action
   * @param records               JavaRDD of hoodieRecords to upsert
   * @param bulkInsertPartitioner User Defined Partitioner
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata bulkInsert(HoodieEngineContext context, String instantTime,
                                                 I records, Option<UserDefinedBulkInsertPartitioner> bulkInsertPartitioner);

  /**
   * Deletes a list of {@link HoodieKey}s from the Hoodie table, at the supplied instantTime {@link HoodieKey}s will be
   * de-duped and non existent keys will be removed before deleting.
   *
   * @param context         Java Spark Context context
   * @param instantTime Instant Time for the action
   * @param keys        {@link List} of {@link HoodieKey}s to be deleted
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata delete(HoodieEngineContext context, String instantTime, K keys);

  /**
   * Upserts the given prepared records into the Hoodie table, at the supplied instantTime.
   * <p>
   * This implementation requires that the input records are already tagged, and de-duped if needed.
   *
   * @param context            Java Spark Context context
   * @param instantTime    Instant Time for the action
   * @param preppedRecords JavaRDD of hoodieRecords to upsert
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata upsertPrepped(HoodieEngineContext context, String instantTime,
                                                    I preppedRecords);

  /**
   * Inserts the given prepared records into the Hoodie table, at the supplied instantTime.
   * <p>
   * This implementation requires that the input records are already tagged, and de-duped if needed.
   *
   * @param context            Java Spark Context context
   * @param instantTime    Instant Time for the action
   * @param preppedRecords JavaRDD of hoodieRecords to upsert
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata insertPrepped(HoodieEngineContext context, String instantTime,
                                                    I preppedRecords);

  /**
   * Bulk Insert the given prepared records into the Hoodie table, at the supplied instantTime.
   * <p>
   * This implementation requires that the input records are already tagged, and de-duped if needed.
   *
   * @param context                   Java Spark Context context
   * @param instantTime           Instant Time for the action
   * @param preppedRecords        JavaRDD of hoodieRecords to upsert
   * @param bulkInsertPartitioner User Defined Partitioner
   * @return HoodieWriteMetadata
   */
  public abstract HoodieWriteMetadata bulkInsertPrepped(HoodieEngineContext context, String instantTime,
                                                        I preppedRecords, Option<UserDefinedBulkInsertPartitioner> bulkInsertPartitioner);

  public HoodieWriteConfig getConfig() {
    return config;
  }

  public HoodieTableMetaClient getMetaClient() {
    return metaClient;
  }

  public Configuration getHadoopConf() {
    return metaClient.getHadoopConf();
  }

  /**
   * Get the view of the file system for this table.
   */
  public TableFileSystemView getFileSystemView() {
    return new HoodieTableFileSystemView(metaClient, getCompletedCommitsTimeline());
  }

  /**
   * Get the base file only view of the file system for this table.
   */
  public BaseFileOnlyView getBaseFileOnlyView() {
    return getViewManager().getFileSystemView(metaClient);
  }

  /**
   * Get the full view of the file system for this table.
   */
  public SliceView getSliceView() {
    return getViewManager().getFileSystemView(metaClient);
  }

  /**
   * Get complete view of the file system for this table with ability to force sync.
   */
  public SyncableFileSystemView getHoodieView() {
    return getViewManager().getFileSystemView(metaClient);
  }

  /**
   * Get only the completed (no-inflights) commit + deltacommit timeline.
   */
  public HoodieTimeline getCompletedCommitsTimeline() {
    return metaClient.getCommitsTimeline().filterCompletedInstants();
  }

  /**
   * Get only the completed (no-inflights) commit timeline.
   */
  public HoodieTimeline getCompletedCommitTimeline() {
    return metaClient.getCommitTimeline().filterCompletedInstants();
  }

  /**
   * Get only the inflights (no-completed) commit timeline.
   */
  public HoodieTimeline getPendingCommitTimeline() {
    return metaClient.getCommitsTimeline().filterPendingExcludingCompaction();
  }

  /**
   * Get only the completed (no-inflights) clean timeline.
   */
  public HoodieTimeline getCompletedCleanTimeline() {
    return getActiveTimeline().getCleanerTimeline().filterCompletedInstants();
  }

  /**
   * Get clean timeline.
   */
  public HoodieTimeline getCleanTimeline() {
    return getActiveTimeline().getCleanerTimeline();
  }

  /**
   * Get only the completed (no-inflights) savepoint timeline.
   */
  public HoodieTimeline getCompletedSavepointTimeline() {
    return getActiveTimeline().getSavePointTimeline().filterCompletedInstants();
  }

  /**
   * Get the list of savepoints in this table.
   */
  public List<String> getSavepoints() {
    return getCompletedSavepointTimeline().getInstants().map(HoodieInstant::getTimestamp).collect(Collectors.toList());
  }

  public HoodieActiveTimeline getActiveTimeline() {
    return metaClient.getActiveTimeline();
  }

  /**
   * Return the index.
   */
  public AbstractHoodieIndex<T,I,K,O,P> getIndex() {
    return index;
  }

  /**
   * Schedule compaction for the instant time.
   *
   * @param context           Spark Context
   * @param instantTime   Instant Time for scheduling compaction
   * @param extraMetadata additional metadata to write into plan
   * @return
   */
  public abstract Option<HoodieCompactionPlan> scheduleCompaction(HoodieEngineContext context,
                                                                  String instantTime,
                                                                  Option<Map<String, String>> extraMetadata);

  /**
   * Run Compaction on the table. Compaction arranges the data so that it is optimized for data access.
   *
   * @param context                   Spark Context
   * @param compactionInstantTime Instant Time
   */
  public abstract HoodieWriteMetadata compact(HoodieEngineContext context,
                                              String compactionInstantTime);

  /**
   * Executes a new clean action.
   *
   * @return information on cleaned file slices
   */
  public abstract HoodieCleanMetadata clean(HoodieEngineContext context, String cleanInstantTime);

  /**
   * Rollback the (inflight/committed) record changes with the given commit time.
   * <pre>
   *   Three steps:
   *   (1) Atomically unpublish this commit
   *   (2) clean indexing data
   *   (3) clean new generated parquet files.
   *   (4) Finally delete .commit or .inflight file, if deleteInstants = true
   * </pre>
   */
  public abstract HoodieRollbackMetadata rollback(HoodieEngineContext context,
                                                  String rollbackInstantTime,
                                                  HoodieInstant commitInstant,
                                                  boolean deleteInstants);

  /**
   * Create a savepoint at the specified instant, so that the table can be restored
   * to this point-in-timeline later if needed.
   */
  public abstract HoodieSavepointMetadata savepoint(HoodieEngineContext context,
                                                    String instantToSavepoint,
                                                    String user,
                                                    String comment);

  /**
   * Restore the table to the given instant. Note that this is a admin table recovery operation
   * that would cause any running queries that are accessing file slices written after the instant to fail.
   */
  public abstract HoodieRestoreMetadata restore(HoodieEngineContext context,
                                                String restoreInstantTime,
                                                String instantToRestore);

  /**
   * Delete Marker directory corresponding to an instant.
   *
   * @param instantTs Instant Time
   */
  public void deleteMarkerDir(String instantTs) {
    try {
      FileSystem fs = getMetaClient().getFs();
      Path markerDir = new Path(metaClient.getMarkerFolderPath(instantTs));
      if (fs.exists(markerDir)) {
        // For append only case, we do not write to marker dir. Hence, the above check
        LOG.info("Removing marker directory=" + markerDir);
        fs.delete(markerDir, true);
      }
    } catch (IOException ioe) {
      throw new HoodieIOException(ioe.getMessage(), ioe);
    }
  }

  /**
   * Ensures all files passed either appear or disappear.
   *
   * @param context              HoodieEngineContext
   * @param groupByPartition Files grouped by partition
   * @param visibility       Appear/Disappear
   */
  public abstract void waitForAllFiles(HoodieEngineContext context, Map<String, List<Pair<String, String>>> groupByPartition, FileVisibility visibility);

  private boolean waitForCondition(String partitionPath, Stream<Pair<String, String>> partitionFilePaths, FileVisibility visibility) {
    final FileSystem fileSystem = metaClient.getRawFs();
    List<String> fileList = partitionFilePaths.map(Pair::getValue).collect(Collectors.toList());
    try {
      getFailSafeConsistencyGuard(fileSystem).waitTill(partitionPath, fileList, visibility);
    } catch (IOException | TimeoutException ioe) {
      LOG.error("Got exception while waiting for files to show up", ioe);
      return false;
    }
    return true;
  }

  private ConsistencyGuard getFailSafeConsistencyGuard(FileSystem fileSystem) {
    return new FailSafeConsistencyGuard(fileSystem, config.getConsistencyGuardConfig());
  }

  public TaskContextSupplier getSparkTaskContextSupplier() {
    return taskContextSupplier;
  }

  /**
   * Ensure that the current writerSchema is compatible with the latest schema of this dataset.
   * <p>
   * When inserting/updating data, we read records using the last used schema and convert them to the
   * GenericRecords with writerSchema. Hence, we need to ensure that this conversion can take place without errors.
   */
  private void validateSchema() throws HoodieUpsertException, HoodieInsertException {

    if (!config.getAvroSchemaValidate() || getActiveTimeline().getCommitsTimeline().filterCompletedInstants().empty()) {
      // Check not required
      return;
    }

    Schema tableSchema;
    Schema writerSchema;
    boolean isValid;
    try {
      TableSchemaResolver schemaUtil = new TableSchemaResolver(getMetaClient());
      writerSchema = HoodieAvroUtils.createHoodieWriteSchema(config.getSchema());
      tableSchema = HoodieAvroUtils.createHoodieWriteSchema(schemaUtil.getTableAvroSchemaWithoutMetadataFields());
      isValid = TableSchemaResolver.isSchemaCompatible(tableSchema, writerSchema);
    } catch (Exception e) {
      throw new HoodieException("Failed to read schema/check compatibility for base path " + metaClient.getBasePath(), e);
    }

    if (!isValid) {
      throw new HoodieException("Failed schema compatibility check for writerSchema :" + writerSchema
          + ", table schema :" + tableSchema + ", base path :" + metaClient.getBasePath());
    }
  }

  public void validateUpsertSchema() throws HoodieUpsertException {
    try {
      validateSchema();
    } catch (HoodieException e) {
      throw new HoodieUpsertException("Failed upsert schema compatibility check.", e);
    }
  }

  public void validateInsertSchema() throws HoodieInsertException {
    try {
      validateSchema();
    } catch (HoodieException e) {
      throw new HoodieInsertException("Failed insert schema compability check.", e);
    }
  }
}
