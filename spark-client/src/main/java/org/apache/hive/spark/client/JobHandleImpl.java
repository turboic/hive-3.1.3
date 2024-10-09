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

import com.google.common.collect.ImmutableList;
import io.netty.util.concurrent.Promise;
import org.apache.hive.spark.counter.SparkCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A handle to a submitted job. Allows for monitoring and controlling of the running remote job.
 */
class JobHandleImpl<T extends Serializable> implements JobHandle<T> {

    private static final Logger LOG = LoggerFactory.getLogger(JobHandleImpl.class);

    private final SparkClientImpl client;
    private final String jobId;
    private final MetricsCollection metrics;
    private final Promise<T> promise;
    private final List<Integer> sparkJobIds;
    private final List<Listener<T>> listeners;
    private volatile State state;
    private volatile SparkCounters sparkCounters;

    JobHandleImpl(SparkClientImpl client, Promise<T> promise, String jobId, List<Listener<T>> listeners) {
        this.client = client;
        this.jobId = jobId;//构造方法传入jobId
        this.promise = promise;
        this.listeners = ImmutableList.copyOf(listeners);
        this.metrics = new MetricsCollection();
        this.sparkJobIds = new CopyOnWriteArrayList<Integer>();
        this.state = State.SENT;
        this.sparkCounters = null;

        synchronized (this.listeners) {
            for (Listener<T> listener : this.listeners) {
                LOG.info("JobHandleImpl 处理实现，初始话监听   ============== {}", listener);
                initializeListener(listener);
            }
        }
    }

    /**
     * Requests a running job to be cancelled.
     */
    @Override
    public boolean cancel(boolean mayInterrupt) {
        if (changeState(State.CANCELLED)) {
            client.cancel(jobId);
            promise.cancel(mayInterrupt);
            return true;
        }
        return false;
    }

    @Override
    public T get() throws ExecutionException, InterruptedException {
        return promise.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
        return promise.get(timeout, unit);
    }

    @Override
    public boolean isCancelled() {
        return promise.isCancelled();
    }

    @Override
    public boolean isDone() {
        return promise.isDone();
    }

    /**
     * The client job ID. This is unrelated to any Spark jobs that might be triggered by the
     * submitted job.
     */
    @Override
    public String getClientJobId() {
        return jobId;
    }

    /**
     * A collection of metrics collected from the Spark jobs triggered by this job.
     * <p>
     * To collect job metrics on the client, Spark jobs must be registered with JobContext::monitor()
     * on the remote end.
     */
    @Override
    public MetricsCollection getMetrics() {
        return metrics;
    }

    @Override
    public List<Integer> getSparkJobIds() {
        return sparkJobIds;
    }

    @Override
    public SparkCounters getSparkCounters() {
        return sparkCounters;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public Throwable getError() {
        return promise.cause();
    }

    public void setSparkCounters(SparkCounters sparkCounters) {
        this.sparkCounters = sparkCounters;
    }

    @SuppressWarnings("unchecked")
    void setSuccess(Object result) {
        // The synchronization here is not necessary, but tests depend on it.
        synchronized (listeners) {
            promise.setSuccess((T) result);
            changeState(State.SUCCEEDED);
        }
    }

    void setFailure(Throwable error) {
        // The synchronization here is not necessary, but tests depend on it.
        synchronized (listeners) {
            promise.setFailure(error);
            changeState(State.FAILED);
        }
    }

    /**
     * Changes the state of this job handle, making sure that illegal state transitions are ignored.
     * Fires events appropriately.
     * <p>
     * As a rule, state transitions can only occur if the current state is "higher" than the current
     * state (i.e., has a higher ordinal number) and is not a "final" state. "Final" states are
     * CANCELLED, FAILED and SUCCEEDED, defined here in the code as having an ordinal number higher
     * than the CANCELLED enum constant.
     */
    boolean changeState(State newState) {
        synchronized (listeners) {
            if (newState.ordinal() > state.ordinal() && state.ordinal() < State.CANCELLED.ordinal()) {
                state = newState;
                for (Listener<T> listener : listeners) {
                    fireStateChange(newState, listener);
                }
                return true;
            }
            return false;
        }
    }

    void addSparkJobId(int sparkJobId) {
        synchronized (listeners) {
            sparkJobIds.add(sparkJobId);
            for (Listener<T> listener : listeners) {
                listener.onSparkJobStarted(this, sparkJobId);
            }
        }
    }

    private void initializeListener(Listener<T> listener) {
        // If current state is a final state, notify of Spark job IDs before notifying about the
        // state transition.
        if (state.ordinal() >= State.CANCELLED.ordinal()) {
            for (Integer id : sparkJobIds) {
                listener.onSparkJobStarted(this, id);
            }
        }

        fireStateChange(state, listener);

        // Otherwise, notify about Spark jobs after the state notification.
        if (state.ordinal() < State.CANCELLED.ordinal()) {
            for (Integer id : sparkJobIds) {
                listener.onSparkJobStarted(this, id);
            }
        }
    }

    private void fireStateChange(State newState, Listener<T> listener) {
        switch (newState) {
            case SENT://发送
                break;
            case QUEUED://队列
                listener.onJobQueued(this);
                break;
            case STARTED://启动
                listener.onJobStarted(this);
                break;
            case CANCELLED://退出
                listener.onJobCancelled(this);
                break;
            case FAILED://失败
                listener.onJobFailed(this, promise.cause());
                break;
            case SUCCEEDED://成功
                try {
                    listener.onJobSucceeded(this, promise.get());
                } catch (Exception e) {
                    // Shouldn't really happen.
                    throw new IllegalStateException(e);
                }
                break;
            default://不支持
                throw new IllegalStateException();
        }
    }

    /**
     * Last attempt at preventing stray jobs from accumulating in SparkClientImpl.
     */
    @Override
    protected void finalize() {
        if (!isDone()) {
            cancel(true);
        }
    }

}
