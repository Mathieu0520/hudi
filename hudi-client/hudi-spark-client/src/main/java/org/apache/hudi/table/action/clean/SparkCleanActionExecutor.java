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

package org.apache.hudi.table.action.clean;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hudi.avro.model.HoodieActionInstant;
import org.apache.hudi.avro.model.HoodieCleanFileInfo;
import org.apache.hudi.avro.model.HoodieCleanerPlan;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieCleanStat;
import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.HoodieSparkEngineContext;
import org.apache.hudi.common.model.CleanFileInfo;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.CleanerUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.HoodieTable;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import scala.Tuple2;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SparkCleanActionExecutor<T extends HoodieRecordPayload> extends BaseCleanActionExecutor<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> {

  private static final Logger LOG = LogManager.getLogger(SparkCleanActionExecutor.class);

  public SparkCleanActionExecutor(HoodieSparkEngineContext context,
                                  HoodieWriteConfig config,
                                  HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                                  String instantTime) {
    super(context, config, table, instantTime);
  }

  @Override
  HoodieCleanerPlan requestClean(HoodieEngineContext context) {
    JavaSparkContext jsc = HoodieSparkEngineContext.getSparkContext(context);
    try {
      CleanPlanner planner = new CleanPlanner<>(table, config);
      Option<HoodieInstant> earliestInstant = planner.getEarliestCommitToRetain();
      List<String> partitionsToClean = planner.getPartitionPathsToClean(earliestInstant);

      if (partitionsToClean.isEmpty()) {
        LOG.info("Nothing to clean here. It is already clean");
        return HoodieCleanerPlan.newBuilder().setPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS.name()).build();
      }
      LOG.info("Total Partitions to clean : " + partitionsToClean.size() + ", with policy " + config.getCleanerPolicy());
      int cleanerParallelism = Math.min(partitionsToClean.size(), config.getCleanerParallelism());
      LOG.info("Using cleanerParallelism: " + cleanerParallelism);

      jsc.setJobGroup(this.getClass().getSimpleName(), "Generates list of file slices to be cleaned");
      Map<String, List<HoodieCleanFileInfo>> cleanOps = jsc
          .parallelize(partitionsToClean, cleanerParallelism)
          .map(partitionPathToClean -> Pair.of(partitionPathToClean, planner.getDeletePaths(partitionPathToClean)))
          .collect().stream()
          .collect(Collectors.toMap(Pair::getKey, y -> CleanerUtils.convertToHoodieCleanFileInfoList(y.getValue())));

      return new HoodieCleanerPlan(earliestInstant
          .map(x -> new HoodieActionInstant(x.getTimestamp(), x.getAction(), x.getState().name())).orElse(null),
          config.getCleanerPolicy().name(), CollectionUtils.createImmutableMap(),
          CleanPlanner.LATEST_CLEAN_PLAN_VERSION, cleanOps);
    } catch (IOException e) {
      throw new HoodieIOException("Failed to schedule clean operation", e);
    }
  }

  private static PairFlatMapFunction<Iterator<Tuple2<String, CleanFileInfo>>, String, PartitionCleanStat>
      deleteFilesFunc(HoodieTable table) {
    return (PairFlatMapFunction<Iterator<Tuple2<String, CleanFileInfo>>, String, PartitionCleanStat>) iter -> {
      Map<String, PartitionCleanStat> partitionCleanStatMap = new HashMap<>();
      FileSystem fs = table.getMetaClient().getFs();
      while (iter.hasNext()) {
        Tuple2<String, CleanFileInfo> partitionDelFileTuple = iter.next();
        String partitionPath = partitionDelFileTuple._1();
        Path deletePath = new Path(partitionDelFileTuple._2().getFilePath());
        String deletePathStr = deletePath.toString();
        Boolean deletedFileResult = deleteFileAndGetResult(fs, deletePathStr);
        if (!partitionCleanStatMap.containsKey(partitionPath)) {
          partitionCleanStatMap.put(partitionPath, new PartitionCleanStat(partitionPath));
        }
        boolean isBootstrapBasePathFile = partitionDelFileTuple._2().isBootstrapBaseFile();
        PartitionCleanStat partitionCleanStat = partitionCleanStatMap.get(partitionPath);
        if (isBootstrapBasePathFile) {
          // For Bootstrap Base file deletions, store the full file path.
          partitionCleanStat.addDeleteFilePatterns(deletePath.toString(), true);
          partitionCleanStat.addDeletedFileResult(deletePath.toString(), deletedFileResult, true);
        } else {
          partitionCleanStat.addDeleteFilePatterns(deletePath.getName(), false);
          partitionCleanStat.addDeletedFileResult(deletePath.getName(), deletedFileResult, false);
        }
      }
      return partitionCleanStatMap.entrySet().stream().map(e -> new Tuple2<>(e.getKey(), e.getValue()))
          .collect(Collectors.toList()).iterator();
    };
  }

  @Override
  List<HoodieCleanStat> clean(HoodieEngineContext context, HoodieCleanerPlan cleanerPlan) {
    JavaSparkContext jsc = HoodieSparkEngineContext.getSparkContext(context);
    int cleanerParallelism = Math.min(
        (int) (cleanerPlan.getFilePathsToBeDeletedPerPartition().values().stream().mapToInt(List::size).count()),
        config.getCleanerParallelism());
    LOG.info("Using cleanerParallelism: " + cleanerParallelism);

    jsc.setJobGroup(this.getClass().getSimpleName(), "Perform cleaning of partitions");
    List<Tuple2<String, PartitionCleanStat>> partitionCleanStats = jsc
        .parallelize(cleanerPlan.getFilePathsToBeDeletedPerPartition().entrySet().stream()
            .flatMap(x -> x.getValue().stream().map(y -> new Tuple2<>(x.getKey(),
                new CleanFileInfo(y.getFilePath(), y.getIsBootstrapBaseFile()))))
            .collect(Collectors.toList()), cleanerParallelism)
        .mapPartitionsToPair(deleteFilesFunc(table))
        .reduceByKey(PartitionCleanStat::merge).collect();

    Map<String, PartitionCleanStat> partitionCleanStatsMap = partitionCleanStats.stream()
        .collect(Collectors.toMap(Tuple2::_1, Tuple2::_2));

    // Return PartitionCleanStat for each partition passed.
    return cleanerPlan.getFilePathsToBeDeletedPerPartition().keySet().stream().map(partitionPath -> {
      PartitionCleanStat partitionCleanStat = partitionCleanStatsMap.containsKey(partitionPath)
          ? partitionCleanStatsMap.get(partitionPath)
          : new PartitionCleanStat(partitionPath);
      HoodieActionInstant actionInstant = cleanerPlan.getEarliestInstantToRetain();
      return HoodieCleanStat.newBuilder().withPolicy(config.getCleanerPolicy()).withPartitionPath(partitionPath)
          .withEarliestCommitRetained(Option.ofNullable(
              actionInstant != null
                  ? new HoodieInstant(HoodieInstant.State.valueOf(actionInstant.getState()),
                  actionInstant.getAction(), actionInstant.getTimestamp())
                  : null))
          .withDeletePathPattern(partitionCleanStat.deletePathPatterns())
          .withSuccessfulDeletes(partitionCleanStat.successDeleteFiles())
          .withFailedDeletes(partitionCleanStat.failedDeleteFiles())
          .withDeleteBootstrapBasePathPatterns(partitionCleanStat.getDeleteBootstrapBasePathPatterns())
          .withSuccessfulDeleteBootstrapBaseFiles(partitionCleanStat.getSuccessfulDeleteBootstrapBaseFiles())
          .withFailedDeleteBootstrapBaseFiles(partitionCleanStat.getFailedDeleteBootstrapBaseFiles())
          .build();
    }).collect(Collectors.toList());
  }
}
