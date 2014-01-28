/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sqoop.mapreduce.hcat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hcatalog.data.HCatRecord;
import org.apache.hcatalog.mapreduce.HCatInputFormat;
import org.apache.sqoop.mapreduce.ExportInputFormat;

/**
 * A combined HCatInputFormat equivalent that allows us to generate the number
 * of splits to the number of map tasks.
 *
 * The logic is simple. We get the list of splits for HCatInputFormat. If it is
 * less than the number of mappers, all is good. Else, we sort the splits by
 * size and assign them to each of the mappers in a simple scheme. After
 * assigning the splits to each of the mapper, for the next round we start with
 * the mapper that got the last split. That way, the size of the split is
 * distributed in a more uniform fashion than a simple round-robin assignment.
 */
public class SqoopHCatExportFormat extends HCatInputFormat {
  public static final Log LOG = LogFactory
    .getLog(SqoopHCatExportFormat.class.getName());

  @Override
  public List<InputSplit> getSplits(JobContext job)
    throws IOException, InterruptedException {
    List<InputSplit> hCatSplits = super.getSplits(job);
    int hCatSplitCount = hCatSplits.size();
    int expectedSplitCount = ExportInputFormat.getNumMapTasks(job);
    if (expectedSplitCount == 0) {
      expectedSplitCount = hCatSplitCount;
    }
    LOG.debug("Expected split count " + expectedSplitCount);
    LOG.debug("HCatInputFormat provided split count " + hCatSplitCount);
    // Sort the splits by length descending.

    Collections.sort(hCatSplits, new Comparator<InputSplit>() {
      @Override
      public int compare(InputSplit is1, InputSplit is2) {
        try {
          return (int) (is2.getLength() - is1.getLength());
        } catch (Exception e) {
          LOG.warn("Exception caught while sorting Input splits " + e);
        }
        return 0;
      }
    });
    List<InputSplit> combinedSplits = new ArrayList<InputSplit>();

    // The number of splits generated by HCatInputFormat is within
    // our limits

    if (hCatSplitCount <= expectedSplitCount) {
      for (InputSplit split : hCatSplits) {
        List<InputSplit> hcSplitList = new ArrayList<InputSplit>();
        hcSplitList.add(split);
        combinedSplits.add(new SqoopHCatInputSplit(hcSplitList));
      }
      return combinedSplits;
    }
    List<List<InputSplit>> combinedSplitList =
      new ArrayList<List<InputSplit>>();
    for (int i = 0; i < expectedSplitCount; i++) {
      combinedSplitList.add(new ArrayList<InputSplit>());
    }
    boolean ascendingAssigment = true;

    int lastSet = 0;
    for (int i = 0; i < hCatSplitCount; ++i) {
      int splitNum = i % expectedSplitCount;
      int currentSet = i / expectedSplitCount;
      if (currentSet != lastSet) {
        ascendingAssigment = !ascendingAssigment;
      }
      if (ascendingAssigment) {
        combinedSplitList.get(splitNum).add(hCatSplits.get(i));
      } else {
        combinedSplitList.
          get(expectedSplitCount - 1 - splitNum).add(hCatSplits.get(i));
      }
      lastSet = currentSet;
    }
    for (int i = 0; i < expectedSplitCount; i++) {
      SqoopHCatInputSplit sqoopSplit =
        new SqoopHCatInputSplit(combinedSplitList.get(i));
      combinedSplits.add(sqoopSplit);
    }

    return combinedSplits;

  }

  @Override
  public RecordReader<WritableComparable, HCatRecord>
    createRecordReader(InputSplit split,
      TaskAttemptContext taskContext)
      throws IOException, InterruptedException {
    LOG.debug("Creating a SqoopHCatRecordReader");
    return new SqoopHCatRecordReader(split, taskContext, this);
  }

  public RecordReader<WritableComparable, HCatRecord>
    createHCatRecordReader(InputSplit split,
      TaskAttemptContext taskContext)
      throws IOException, InterruptedException {
    LOG.debug("Creating a base HCatRecordReader");
    return super.createRecordReader(split, taskContext);
  }
}
