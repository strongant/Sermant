/*
 * Copyright (C) 2022-2022 Huawei Technologies Co., Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.integration.flow;

import com.huaweicloud.integration.utils.RequestUtils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流控测试
 *
 * @author zhouss
 * @since 2022-09-16
 */
@EnabledIfEnvironmentVariable(named = "TEST_TYPE", matches = "common")
public class FlowControlTest {
    private static final String BASE_URL = "http://127.0.0.1:28020/consumer/flow/";
    private static final int RATE_LIMITING_REQUEST_COUNT = 10;
    private static final int BREAKER_REQUEST_COUNT = 10;
    private static final String BREAKER_MSG = "is OPEN and does not permit further calls";
    private static final String RATE_LIMITING_MSG = "Rate Limited";

    /**
     * 限流测试
     */
    @Test
    public void testRateLimiting() {
        rateTest("rateLimiting");
    }

    /**
     * 限流测试-应用匹配测试
     */
    @Test
    public void testRateLimitingWithApplication() {
        rateTest("rateLimitingWithApplication");
    }

    /**
     * 限流测试-应用匹配测试
     */
    @Test
    public void testRateLimitingWithHeader() {
        rateTest("rateLimitingWithHeader?key=key&value=attachement&key2=key2&value2=999");
        final AtomicBoolean check = new AtomicBoolean();
        process("rateLimitingWithHeader?key=key&value=val&key2=key2&value2=998", RATE_LIMITING_MSG,
            RATE_LIMITING_REQUEST_COUNT, check);
        Assertions.assertFalse(check.get());
    }

    /**
     * 限流测试-版本测试
     */
    @Test
    public void testRateLimitingWithVersion() {
        rateTest("rateLimitingWithVersion");
    }

    /**
     * 测试熔断-慢调用熔断
     */
    @Test
    public void cirEx() {
        test("cirEx", BREAKER_MSG, BREAKER_REQUEST_COUNT);
    }

    /**
     * 实例隔离-慢调用熔断
     */
    @Test
    public void instanceSlowInvoker() {
        test("instanceSlowInvoker", BREAKER_MSG, BREAKER_REQUEST_COUNT);
    }

    /**
     * 实例隔离-异常
     */
    @Test
    public void instanceEx() {
        test("instanceEx", BREAKER_MSG, BREAKER_REQUEST_COUNT);
    }

    /**
     * 错误注入-返回空
     */
    @Test
    public void faultNull() {
        final String result = RequestUtils.get(BASE_URL + "faultNull", Collections.emptyMap(), String.class);
        Assertions.assertNull(result);
    }

    /**
     * 错误注入-抛异常
     */
    @Test
    public void faultThrowEx() {
        test("faultThrowEx", "Request has been aborted by fault-ThrowException", 1);
    }

    /**
     * 错误注入-返回空
     */
    @Test
    public void faultDelay() {
        final long start = System.currentTimeMillis();
        long delay = 2000L;
        RequestUtils.get(BASE_URL + "faultDelay", Collections.emptyMap(), String.class);
        Assertions.assertTrue((System.currentTimeMillis() - start) >= delay);
    }

    /**
     * 隔离仓测试
     */
    @Test
    public void bulkHead() throws InterruptedException {
        final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(5, 5, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100));
        int cycle = 5;
        final CountDownLatch countDownLatch = new CountDownLatch(cycle);
        final AtomicBoolean expected = new AtomicBoolean();
        for (int i = 0; i < cycle; i++) {
            threadPoolExecutor.execute(() -> {
                try {
                    process("/bulkhead", "Bulkhead is full and does not permit further calls",
                        RATE_LIMITING_REQUEST_COUNT, expected);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();
        Assertions.assertTrue(expected.get());
        threadPoolExecutor.shutdown();
    }

    /**
     * 重试
     */
    @Test
    public void retry() {
        final String result = RequestUtils.get(BASE_URL + "retry", Collections.emptyMap(), String.class);
        Assertions.assertEquals(result, "3");
    }

    /**
     * 负载均衡测试
     */
    @Test
    public void lb() {
        final String result = RequestUtils.get(BASE_URL + "lb", Collections.emptyMap(), String.class);
        Assertions.assertEquals(result, "leastactive");
    }

    /**
     * 测试熔断-异常
     */
    @Test
    public void cirSlowInvoker() {
        test("cirSlowInvoker", BREAKER_MSG, BREAKER_REQUEST_COUNT);
    }

    private void rateTest(String api) {
        test(api, RATE_LIMITING_MSG, RATE_LIMITING_REQUEST_COUNT);
    }

    private void test(String api, String msg, int requestCount) {
        final AtomicBoolean check = new AtomicBoolean();
        process(api, msg, requestCount, check);
        Assertions.assertTrue(check.get());
    }

    private void process(String api, String flowControlMsg, int requestCount, AtomicBoolean check) {
        String url = BASE_URL + api;
        for (int i = 0; i < requestCount; i++) {
            try {
                RequestUtils.get(url, Collections.emptyMap(), String.class,
                    (clientHttpResponse, result) -> {
                        if (result.contains(flowControlMsg)) {
                            check.set(true);
                        }
                        return result;
                    });
            } catch (Exception ex) {
                if (ex.getMessage().contains(flowControlMsg)) {
                    check.set(true);
                }
            }
        }
    }
}
