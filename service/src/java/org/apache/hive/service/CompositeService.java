/*
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

package org.apache.hive.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hive.conf.HiveConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompositeService.
 */
public class CompositeService extends AbstractService {

    private static final Logger LOG = LoggerFactory.getLogger(CompositeService.class);

    private final List<Service> serviceList = new ArrayList<Service>();

    public CompositeService(String name) {
        super(name);
    }

    public Collection<Service> getServices() {
        return Collections.unmodifiableList(serviceList);
    }

    protected synchronized void addService(Service service) {
        LOG.info("使用枷锁的方式添加了服务 ======= {}", service.getName());
        serviceList.add(service);
    }

    protected synchronized boolean removeService(Service service) {
      LOG.info("使用枷锁的方式移除了服务 ======= {}", service.getName());

      return serviceList.remove(service);
    }

    @Override
    public synchronized void init(HiveConf hiveConf) {
        for (Service service : serviceList) {
          LOG.info("使用枷锁的方式初始化了服务 ======= {}", service.getName());

          service.init(hiveConf);
        }
        super.init(hiveConf);
    }

    @Override
    public synchronized void start() {
        int i = 0;
        try {
            for (int n = serviceList.size(); i < n; i++) {
                Service service = serviceList.get(i);
                service.start();
              LOG.info("使用枷锁的方式启动了服务 ======= {}", service.getName());

            }
            super.start();
        } catch (Throwable e) {
            LOG.error("Error starting services " + getName(), e);
            // Note that the state of the failed service is still INITED and not
            // STARTED. Even though the last service is not started completely, still
            // call stop() on all services including failed service to make sure cleanup
            // happens.
            stop(i);
            throw new ServiceException("Failed to Start " + getName(), e);
        }

    }

    @Override
    public synchronized void stop() {
        if (this.getServiceState() == STATE.STOPPED) {
            // The base composite-service is already stopped, don't do anything again.
            return;
        }
        if (serviceList.size() > 0) {
            stop(serviceList.size() - 1);
        }
        super.stop();
    }

    private synchronized void stop(int numOfServicesStarted) {
        // stop in reserve order of start
        for (int i = numOfServicesStarted; i >= 0; i--) {
            Service service = serviceList.get(i);
            try {
              LOG.info("使用枷锁的方式停止了服务 ======= {}", service.getName());

              service.stop();
            } catch (Throwable t) {
                LOG.info("Error stopping " + service.getName(), t);
            }
        }
    }

    /**
     * JVM Shutdown hook for CompositeService which will stop the given
     * CompositeService gracefully in case of JVM shutdown.
     */
    public static class CompositeServiceShutdownHook implements Runnable {

        private final CompositeService compositeService;

        public CompositeServiceShutdownHook(CompositeService compositeService) {
            this.compositeService = compositeService;
        }

        @Override
        public void run() {
            try {
                // Stop the Composite Service
              LOG.info("使用线程停止了 ======= {}", compositeService.getName());

              compositeService.stop();
            } catch (Throwable t) {
                LOG.info("Error stopping " + compositeService.getName(), t);
            }
        }
    }


}
