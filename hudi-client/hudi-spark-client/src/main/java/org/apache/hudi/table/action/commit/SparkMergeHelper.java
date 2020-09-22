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

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.common.util.queue.BoundedInMemoryExecutor;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.execution.SparkBoundedInMemoryExecutor;
import org.apache.hudi.io.HoodieMergeHandle;
import org.apache.hudi.io.storage.HoodieFileReader;
import org.apache.hudi.io.storage.HoodieFileReaderFactory;
import org.apache.hudi.table.HoodieTable;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.io.IOException;
import java.util.Iterator;

public class SparkMergeHelper<T extends HoodieRecordPayload> extends AbstractMergeHelper<T, JavaRDD<HoodieRecord<T>>,
    JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> {

  private static class MergeHelperHolder {
    private static final SparkMergeHelper SPARK_MERGE_HELPER = new SparkMergeHelper();
  }

  public static SparkMergeHelper newInstance() {
    return SparkMergeHelper.MergeHelperHolder.SPARK_MERGE_HELPER;
  }

  @Override
  public void runMerge(HoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                       HoodieMergeHandle<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> upsertHandle) throws IOException {
    final boolean externalSchemaTransformation = table.getConfig().shouldUseExternalSchemaTransformation();
    Configuration cfgForHoodieFile = new Configuration(table.getHadoopConf());
    HoodieMergeHandle<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> mergeHandle = upsertHandle;
    HoodieBaseFile baseFile = mergeHandle.baseFileForMerge();

    final GenericDatumWriter<GenericRecord> gWriter;
    final GenericDatumReader<GenericRecord> gReader;
    Schema readSchema;
    if (externalSchemaTransformation || baseFile.getBootstrapBaseFile().isPresent()) {
      readSchema = HoodieFileReaderFactory.getFileReader(table.getHadoopConf(), mergeHandle.getOldFilePath()).getSchema();
      gWriter = new GenericDatumWriter<>(readSchema);
      gReader = new GenericDatumReader<>(readSchema, mergeHandle.getWriterSchemaWithMetafields());
    } else {
      gReader = null;
      gWriter = null;
      readSchema = mergeHandle.getWriterSchemaWithMetafields();
    }

    BoundedInMemoryExecutor<GenericRecord, GenericRecord, Void> wrapper = null;
    HoodieFileReader<GenericRecord> reader = HoodieFileReaderFactory.<T, GenericRecord>getFileReader(cfgForHoodieFile, mergeHandle.getOldFilePath());
    try {
      final Iterator<GenericRecord> readerIterator;
      if (baseFile.getBootstrapBaseFile().isPresent()) {
        readerIterator = getMergingIterator(table, mergeHandle, baseFile, reader, readSchema, externalSchemaTransformation);
      } else {
        readerIterator = reader.getRecordIterator(readSchema);
      }

      ThreadLocal<BinaryEncoder> encoderCache = new ThreadLocal<>();
      ThreadLocal<BinaryDecoder> decoderCache = new ThreadLocal<>();
      wrapper = new SparkBoundedInMemoryExecutor(table.getConfig(), readerIterator,
          new UpdateHandler(mergeHandle), record -> {
        if (!externalSchemaTransformation) {
          return record;
        }
        return transformRecordBasedOnNewSchema(gReader, gWriter, encoderCache, decoderCache, (GenericRecord) record);
      });
      wrapper.execute();
    } catch (Exception e) {
      throw new HoodieException(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
      mergeHandle.close();
      if (null != wrapper) {
        wrapper.shutdownNow();
      }
    }
  }
}
