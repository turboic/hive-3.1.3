/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.spark.client;

import com.google.common.base.Throwables;
import org.apache.hive.spark.client.metrics.Metrics;
import org.apache.hive.spark.client.rpc.RpcDispatcher;
import org.apache.hive.spark.counter.SparkCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class BaseProtocol extends RpcDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(BaseProtocol.class);


  public static class CancelJob implements Serializable {

    final String id;

    public CancelJob(String id) {
      this.id = id;
    }

    public CancelJob() {
      this(null);
    }

  }

  public static class EndSession implements Serializable {

  }

  public static class Error implements Serializable {

    final String cause;

    public Error(String cause) {
      this.cause = cause;
    }

    public Error() {
      this(null);
    }

  }

  public static class JobMetrics implements Serializable {

    final String jobId;
    final int sparkJobId;
    final int stageId;
    final long taskId;
    final Metrics metrics;

    public JobMetrics(String jobId, int sparkJobId, int stageId, long taskId, Metrics metrics) {
      this.jobId = jobId;
      this.sparkJobId = sparkJobId;
      this.stageId = stageId;
      this.taskId = taskId;
      this.metrics = metrics;
    }

    public JobMetrics() {
      this(null, -1, -1, -1, null);
    }

  }

  public static class JobRequest<T extends Serializable> implements Serializable {

    final String id;
    final Job<T> job;

    public JobRequest(String id, Job<T> job) {
      this.id = id;
      this.job = job;
    }

    public JobRequest() {
      this(null, null);
    }

  }

  public static class JobResult<T extends Serializable> implements Serializable {

    final String id;
    final T result;
    final String error;
    final SparkCounters sparkCounters;

    public JobResult(String id, T result, Throwable error, SparkCounters sparkCounters) {
      this.id = id;
      this.result = result;
      this.error = error != null ? Throwables.getStackTraceAsString(error) : null;
      this.sparkCounters = sparkCounters;

      LOG.info("JobResult id ======= {}",id);


      LOG.info("JobResult Throwable ======= ",error);
      LOG.info("JobResult sparkCounters ======= {}",sparkCounters);
    }

    public JobResult() {
      this(null, null, null, null);
    }

  }

  public static class JobStarted implements Serializable {

    final String id;

    public JobStarted(String id) {
      this.id = id;
    }

    public JobStarted() {
      this(null);
    }

  }

  /**
   * Inform the client that a new spark job has been submitted for the client job.
   */
  public static class JobSubmitted implements Serializable {
    final String clientJobId;
    final int sparkJobId;

    public JobSubmitted(String clientJobId, int sparkJobId) {
      this.clientJobId = clientJobId;
      this.sparkJobId = sparkJobId;
    }

    public JobSubmitted() {
      this(null, -1);
    }
  }

  public static class SyncJobRequest<T extends Serializable> implements Serializable {

    final Job<T> job;

    public SyncJobRequest(Job<T> job) {
      this.job = job;
    }

    public SyncJobRequest() {
      this(null);
    }

  }
}
