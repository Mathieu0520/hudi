package org.apache.hudi.writer.client;

import org.apache.hadoop.conf.Configuration;
import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.common.HoodieRollbackStat;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieRollingStat;
import org.apache.hudi.common.model.HoodieRollingStatMetadata;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.writer.WriteStatus;
import org.apache.hudi.writer.client.embedded.EmbeddedTimelineService;
import org.apache.hudi.writer.config.HoodieWriteConfig;
import org.apache.hudi.writer.exception.HoodieCommitException;
import org.apache.hudi.writer.exception.HoodieRollbackException;
import org.apache.hudi.writer.index.HoodieIndex;
import org.apache.hudi.writer.table.HoodieTable;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *  Abstract Write Client providing functionality for performing commit, index updates and rollback
 *  Reused for regular write operations like upsert/insert/bulk-insert.. as well as bootstrap
 * @param <T> Sub type of HoodieRecordPayload
 */
public abstract class AbstractHoodieWriteClient<T extends HoodieRecordPayload> extends AbstractHoodieClient {
  private static final Logger LOG = LogManager.getLogger(AbstractHoodieWriteClient.class);
  private static final String UPDATE_STR = "update";
  private final transient HoodieIndex<T> index;
  private transient WriteOperationType operationType;
  protected AbstractHoodieWriteClient(Configuration hadoopConf, HoodieIndex index, HoodieWriteConfig clientConfig,
                                      Option<EmbeddedTimelineService> timelineServer) {
    super(hadoopConf, clientConfig, timelineServer);
    this.index = index;
  }

  protected HoodieTable getTableAndInitCtx(WriteOperationType operationType) {
    HoodieTableMetaClient metaClient = createMetaClient(true);
    if (operationType == WriteOperationType.DELETE) {
      setWriteSchemaFromLastInstant(metaClient);
    }
    // Create a Hoodie table which encapsulated the commits and files visible
    HoodieTable table = HoodieTable.create(metaClient, config, hadoopConf);
    return table;
  }

  /**
   * Sets write schema from last instant since deletes may not have schema set in the config.
   */
  private void setWriteSchemaFromLastInstant(HoodieTableMetaClient metaClient) {
    try {
      HoodieActiveTimeline activeTimeline = metaClient.getActiveTimeline();
      Option<HoodieInstant> lastInstant =
          activeTimeline.filterCompletedInstants().filter(s -> s.getAction().equals(metaClient.getCommitActionType()))
              .lastInstant();
      if (lastInstant.isPresent()) {
        HoodieCommitMetadata commitMetadata = HoodieCommitMetadata.fromBytes(
            activeTimeline.getInstantDetails(lastInstant.get()).get(), HoodieCommitMetadata.class);
        if (commitMetadata.getExtraMetadata().containsKey(HoodieCommitMetadata.SCHEMA_KEY)) {
          config.setSchema(commitMetadata.getExtraMetadata().get(HoodieCommitMetadata.SCHEMA_KEY));
        } else {
          throw new HoodieIOException("Latest commit does not have any schema in commit metadata");
        }
      } else {
        throw new HoodieIOException("Deletes issued without any prior commits");
      }
    } catch (IOException e) {
      throw new HoodieIOException("IOException thrown while reading last commit metadata", e);
    }
  }

  protected List<WriteStatus> updateIndexAndCommitIfNeeded(List<WriteStatus> writeStatusRDD, HoodieTable<T> table,
                                                           String instantTime) {
    // Update the index back
    List<WriteStatus> statuses = index.updateLocation(writeStatusRDD, hadoopConf, table);
    // Trigger the insert and collect statuses
    commitOnAutoCommit(instantTime, statuses, table.getMetaClient().getCommitActionType());
    return statuses;
  }

  protected void commitOnAutoCommit(String instantTime, List<WriteStatus> resultRDD, String actionType) {
    if (config.shouldAutoCommit()) {
      LOG.info("Auto commit enabled: Committing " + instantTime);
      boolean commitResult = commit(instantTime, resultRDD, Option.empty(), actionType);
      if (!commitResult) {
        throw new HoodieCommitException("Failed to commit " + instantTime);
      }
    } else {
      LOG.info("Auto commit disabled for " + instantTime);
    }
  }

  private boolean commit(String instantTime, List<WriteStatus> writeStatuses,
                         Option<Map<String, String>> extraMetadata, String actionType) {

    LOG.info("Committing " + instantTime);
    // Create a Hoodie table which encapsulated the commits and files visible
    HoodieTable<T> table = HoodieTable.create(config, hadoopConf);

    HoodieActiveTimeline activeTimeline = table.getActiveTimeline();
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();

    List<HoodieWriteStat> stats = writeStatuses.stream().map(WriteStatus::getStat).collect(Collectors.toList());

    updateMetadataAndRollingStats(actionType, metadata, stats);

    // Finalize write
    finalizeWrite(table, instantTime, stats);

    // add in extra metadata
    if (extraMetadata.isPresent()) {
      extraMetadata.get().forEach(metadata::addMetadata);
    }
    metadata.addMetadata(HoodieCommitMetadata.SCHEMA_KEY, config.getSchema());
    metadata.setOperationType(operationType);

    try {
      activeTimeline.saveAsComplete(new HoodieInstant(true, actionType, instantTime),
          Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

      postCommit(metadata, instantTime, extraMetadata);

      LOG.info("Committed " + instantTime);
    } catch (IOException e) {
      throw new HoodieCommitException("Failed to complete commit " + config.getBasePath() + " at time " + instantTime,
          e);
    }
    return true;
  }

  /**
   * Post Commit Hook. Derived classes use this method to perform post-commit processing
   * @param metadata      Commit Metadata corresponding to committed instant
   * @param instantTime   Instant Time
   * @param extraMetadata Additional Metadata passed by user
   * @throws IOException in case of error
   */
  protected abstract void postCommit(HoodieCommitMetadata metadata, String instantTime,
                                     Option<Map<String, String>> extraMetadata) throws IOException;

  /**
   * Commit changes performed at the given instantTime marker.
   */
  public boolean commit(String instantTime, List<WriteStatus> writeStatuses,
                        Option<Map<String, String>> extraMetadata) {
    HoodieTableMetaClient metaClient = createMetaClient(false);
    return commit(instantTime, writeStatuses, extraMetadata, metaClient.getCommitActionType());
  }

  protected void rollbackInternal(String commitToRollback) {
    final String startRollbackTime = HoodieActiveTimeline.createNewInstantTime();
    // Create a Hoodie table which encapsulated the commits and files visible
    try {
      // Create a Hoodie table which encapsulated the commits and files visible
      HoodieTable<T> table = HoodieTable.create(config, hadoopConf);
      Option<HoodieInstant> rollbackInstantOpt =
          Option.fromJavaOptional(table.getActiveTimeline().getCommitsTimeline().getInstants()
              .filter(instant -> HoodieActiveTimeline.EQUAL.test(instant.getTimestamp(), commitToRollback))
              .findFirst());

      if (rollbackInstantOpt.isPresent()) {
        List<HoodieRollbackStat> stats = doRollbackAndGetStats(rollbackInstantOpt.get());
        finishRollback(stats, Collections.singletonList(commitToRollback), startRollbackTime);
      }
    } catch (IOException e) {
      throw new HoodieRollbackException("Failed to rollback " + config.getBasePath() + " commits " + commitToRollback,
          e);
    }
  }

  private void finishRollback(List<HoodieRollbackStat> rollbackStats,
                              List<String> commitsToRollback, final String startRollbackTime) throws IOException {
    HoodieTable<T> table = HoodieTable.create(config, hadoopConf);
    Option<Long> durationInMs = Option.empty();
    long numFilesDeleted = rollbackStats.stream().mapToLong(stat -> stat.getSuccessDeleteFiles().size()).sum();
    HoodieRollbackMetadata rollbackMetadata = TimelineMetadataUtils
        .convertRollbackMetadata(startRollbackTime, durationInMs, commitsToRollback, rollbackStats);
    //TODO: varadarb - This will be fixed when Rollback transition mimics that of commit
    table.getActiveTimeline().createNewInstant(new HoodieInstant(HoodieInstant.State.INFLIGHT, HoodieTimeline.ROLLBACK_ACTION,
        startRollbackTime));
    table.getActiveTimeline().saveAsComplete(
        new HoodieInstant(true, HoodieTimeline.ROLLBACK_ACTION, startRollbackTime),
        TimelineMetadataUtils.serializeRollbackMetadata(rollbackMetadata));
    LOG.info("Rollback of Commits " + commitsToRollback + " is complete");

    if (!table.getActiveTimeline().getCleanerTimeline().empty()) {
      LOG.info("Cleaning up older rollback meta files");
      // Cleanup of older cleaner meta files
      // TODO - make the commit archival generic and archive rollback metadata
      FSUtils.deleteOlderRollbackMetaFiles(fs, table.getMetaClient().getMetaPath(),
          table.getActiveTimeline().getRollbackTimeline().getInstants());
    }
  }

  protected List<HoodieRollbackStat> doRollbackAndGetStats(final HoodieInstant instantToRollback) throws
      IOException {
    final String commitToRollback = instantToRollback.getTimestamp();
    HoodieTable<T> table = HoodieTable.create(config, hadoopConf);
    HoodieTimeline inflightAndRequestedCommitTimeline = table.getPendingCommitTimeline();
    HoodieTimeline commitTimeline = table.getCompletedCommitsTimeline();
    // Check if any of the commits is a savepoint - do not allow rollback on those commits
    List<String> savepoints = table.getCompletedSavepointTimeline().getInstants().map(HoodieInstant::getTimestamp)
        .collect(Collectors.toList());
    savepoints.forEach(s -> {
      if (s.contains(commitToRollback)) {
        throw new HoodieRollbackException(
            "Could not rollback a savepointed commit. Delete savepoint first before rolling back" + s);
      }
    });

    if (commitTimeline.empty() && inflightAndRequestedCommitTimeline.empty()) {
      // nothing to rollback
      LOG.info("No commits to rollback " + commitToRollback);
    }

    // Make sure only the last n commits are being rolled back
    // If there is a commit in-between or after that is not rolled back, then abort

    if ((commitToRollback != null) && !commitTimeline.empty()
        && !commitTimeline.findInstantsAfter(commitToRollback, Integer.MAX_VALUE).empty()) {
      throw new HoodieRollbackException(
          "Found commits after time :" + commitToRollback + ", please rollback greater commits first");
    }

    List<String> inflights = inflightAndRequestedCommitTimeline.getInstants().map(HoodieInstant::getTimestamp)
        .collect(Collectors.toList());
    if ((commitToRollback != null) && !inflights.isEmpty()
        && (inflights.indexOf(commitToRollback) != inflights.size() - 1)) {
      throw new HoodieRollbackException(
          "Found in-flight commits after time :" + commitToRollback + ", please rollback greater commits first");
    }

    List<HoodieRollbackStat> stats = table.rollback(hadoopConf, instantToRollback, true);

    LOG.info("Deleted inflight commits " + commitToRollback);

    // cleanup index entries
    if (!getIndex().rollbackCommit(commitToRollback)) {
      throw new HoodieRollbackException("Rollback index changes failed, for time :" + commitToRollback);
    }
    LOG.info("Index rolled back for commits " + commitToRollback);
    return stats;
  }


  /**
   * Finalize Write operation.
   * @param table  HoodieTable
   * @param instantTime Instant Time
   * @param stats Hoodie Write Stat
   */
  protected void finalizeWrite(HoodieTable<T> table, String instantTime, List<HoodieWriteStat> stats) {
    try {
      table.finalizeWrite(hadoopConf, instantTime, stats);
    } catch (HoodieIOException ioe) {
      throw new HoodieCommitException("Failed to complete commit " + instantTime + " due to finalize errors.", ioe);
    }
  }

  private void updateMetadataAndRollingStats(String actionType, HoodieCommitMetadata metadata,
                                             List<HoodieWriteStat> writeStats) {
    // TODO : make sure we cannot rollback / archive last commit file
    try {
      // Create a Hoodie table which encapsulated the commits and files visible
      HoodieTable table = HoodieTable.create(config, hadoopConf);
      // 0. All of the rolling stat management is only done by the DELTA commit for MOR and COMMIT for COW other wise
      // there may be race conditions
      HoodieRollingStatMetadata rollingStatMetadata = new HoodieRollingStatMetadata(actionType);
      // 1. Look up the previous compaction/commit and get the HoodieCommitMetadata from there.
      // 2. Now, first read the existing rolling stats and merge with the result of current metadata.

      // Need to do this on every commit (delta or commit) to support COW and MOR.

      for (HoodieWriteStat stat : writeStats) {
        String partitionPath = stat.getPartitionPath();
        // TODO: why is stat.getPartitionPath() null at times here.
        metadata.addWriteStat(partitionPath, stat);
        HoodieRollingStat hoodieRollingStat = new HoodieRollingStat(stat.getFileId(),
            stat.getNumWrites() - (stat.getNumUpdateWrites() - stat.getNumDeletes()), stat.getNumUpdateWrites(),
            stat.getNumDeletes(), stat.getTotalWriteBytes());
        rollingStatMetadata.addRollingStat(partitionPath, hoodieRollingStat);
      }
      // The last rolling stat should be present in the completed timeline
      Option<HoodieInstant> lastInstant =
          table.getActiveTimeline().getCommitsTimeline().filterCompletedInstants().lastInstant();
      if (lastInstant.isPresent()) {
        HoodieCommitMetadata commitMetadata = HoodieCommitMetadata.fromBytes(
            table.getActiveTimeline().getInstantDetails(lastInstant.get()).get(), HoodieCommitMetadata.class);
        Option<String> lastRollingStat = Option
            .ofNullable(commitMetadata.getExtraMetadata().get(HoodieRollingStatMetadata.ROLLING_STAT_METADATA_KEY));
        if (lastRollingStat.isPresent()) {
          rollingStatMetadata = rollingStatMetadata
              .merge(HoodieCommitMetadata.fromBytes(lastRollingStat.get().getBytes(), HoodieRollingStatMetadata.class));
        }
      }
      metadata.addMetadata(HoodieRollingStatMetadata.ROLLING_STAT_METADATA_KEY, rollingStatMetadata.toJsonString());
    } catch (IOException io) {
      throw new HoodieCommitException("Unable to save rolling stats");
    }
  }

  public void setOperationType(WriteOperationType operationType) {
    this.operationType = operationType;
  }

  public HoodieIndex<T> getIndex() {
    return index;
  }

  public WriteOperationType getOperationType() {
    return this.operationType;
  }

}
