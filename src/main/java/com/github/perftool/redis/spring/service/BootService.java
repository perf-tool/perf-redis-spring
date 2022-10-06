/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.github.perftool.redis.spring.service;


import com.github.perftool.redis.spring.config.RedisConfig;
import com.github.perftool.redis.spring.metrics.MetricFactory;
import com.github.perftool.redis.spring.utils.IDUtils;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class BootService {


    private RedisConfig redisConfig;


    private RedisService redisService;

    private MetricsService metricsService;

    public BootService(@Autowired RedisConfig redisConfig,
                       @Autowired RedisService redisService,
                       @Autowired MetricsService metricsService) {

        this.redisConfig = redisConfig;
        this.redisService = redisService;
        this.metricsService = metricsService;
    }

    @PostConstruct
    public void init() {
        log.info("init redis.");
        MetricFactory metricFactory = metricsService.acquireMetricFactory();
        ExecutorService executorService =
                Executors.newSingleThreadExecutor(new DefaultThreadFactory("redis-init"));
        executorService.execute(() -> BootService.this.initAsync(metricFactory));
    }

    /**
     * use init async to let the springboot framework run
     */
    public void initAsync(MetricFactory metricFactory) {
        redisService.initDatasource();
        Set<String> nowKeys = redisService.listKeys();
        log.info("current key size is {}", nowKeys.size());
        log.debug("the now key : {}", nowKeys);
        List<String> keys = new ArrayList<>();
        int needDataSetSize = redisConfig.dataSetSize - nowKeys.size();
        if (needDataSetSize > 0) {
            keys = IDUtils.getTargetIds(needDataSetSize);
            redisService.presetData(metricFactory, keys);
        }
        keys.addAll(nowKeys);
        log.info("key size now is {}", keys.size());
        redisService.boot(metricFactory, keys);
    }

}

