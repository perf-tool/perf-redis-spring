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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.github.perftool.redis.spring.config.RedisConfig;
import com.github.perftool.redis.spring.metrics.MetricFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


@Slf4j
@Service
public class RedisService {

    private RedisConfig redisConfig;

    private RedisTemplate<String, Object> redisTemplate;

    private final Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer =
            new Jackson2JsonRedisSerializer<>(Object.class);

    public RedisService(@Autowired RedisConfig redisConfig) {
        this.redisConfig = redisConfig;
    }

    public void initDatasource() {
        this.redisTemplate = createRedisTemplate();
    }
    public Set<String> listKeys() {
        Set<String> set = new HashSet<>();
        Cursor<String> scan = this.redisTemplate.scan(ScanOptions.
                scanOptions()
                .count(redisConfig.dataSetSize)
                .match("*")
                .build());
        while (scan.hasNext()) {
            set.add(scan.next());
        }
        return set;
    }

    public void presetData(MetricFactory metricFactory, List<String> keys) {
        ExecutorService threadPool = Executors.newFixedThreadPool(redisConfig.presetThreadNum);
        RedisThread redisStorageThread = new RedisThread(keys, metricFactory, redisConfig, redisTemplate);
        List<Callable<Object>> callableList =
                keys.stream().map(s -> Executors.callable(() -> redisStorageThread.insertData(s)))
                        .collect(Collectors.toList());
        try {
            threadPool.invokeAll(callableList);
        } catch (InterruptedException e) {
            log.error("preset s3 data failed ", e);
        }
    }

    public void boot(MetricFactory metricFactory, List<String> keys) {
        for (int i = 0; i < redisConfig.threadNum; i++) {
            new RedisThread(keys, metricFactory, redisConfig, redisTemplate).start();
        }
    }

    private LettuceConnectionFactory lettuceConnectionFactory() {
        GenericObjectPoolConfig genericObjectPoolConfig = new GenericObjectPoolConfig();
        genericObjectPoolConfig.setMaxIdle(redisConfig.maxIdle);
        genericObjectPoolConfig.setMinIdle(redisConfig.minIdle);
        genericObjectPoolConfig.setMaxTotal(redisConfig.maxActive);
        RedisConfiguration redisConfiguration;
        if (redisConfig.redisClusterEnable) {
            RedisClusterConfiguration redisClusterConfiguration = new RedisClusterConfiguration();
            String[] urls = redisConfig.clusterNodeUrl.split(",");
            for (String s : urls) {
                String[] url = s.split(":");
                redisClusterConfiguration.addClusterNode(new RedisNode(url[0], Integer.parseInt(url[1])));
            }
            redisClusterConfiguration.setUsername(redisConfig.user);
            redisClusterConfiguration.setPassword(redisConfig.password);
            redisConfiguration = redisClusterConfiguration;
        } else {
            RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
            String[] url = redisConfig.clusterNodeUrl.split(",");
            redisStandaloneConfiguration.setDatabase(redisConfig.database);
            redisStandaloneConfiguration.setHostName(url[0]);
            redisStandaloneConfiguration.setPort(Integer.parseInt(url[1]));
            redisStandaloneConfiguration.setPassword(RedisPassword.of(redisConfig.password));
            redisConfiguration = redisStandaloneConfiguration;
        }

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(redisConfig.timeout))
                .shutdownTimeout(Duration.ofMillis(redisConfig.shutDownTimeout))
                .poolConfig(genericObjectPoolConfig)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfiguration, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisTemplate<String, Object> createRedisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(lettuceConnectionFactory());
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        jackson2JsonRedisSerializer.setObjectMapper(mapper);
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
