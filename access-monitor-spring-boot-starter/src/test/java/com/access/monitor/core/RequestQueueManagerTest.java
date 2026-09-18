package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求队列管理器测试，覆盖并发许可、队列满拒绝、超时拒绝和按 Key 隔离。
 */
class RequestQueueManagerTest {

    private AccessMonitorProperties newProperties() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getQueue().setEnabled(true);
        properties.getQueue().setMaxConcurrentPerKey(2);
        properties.getQueue().setMaxQueueSizePerKey(0);
        properties.getQueue().setQueueTimeout(Duration.ofMillis(200));
        return properties;
    }

    @Test
    void allowsUpToMaxConcurrentPerKey() throws InterruptedException {
        RequestQueueManager manager = new RequestQueueManager(newProperties());

        assertTrue(manager.tryAcquire("ip:1.1.1.1"));
        assertTrue(manager.tryAcquire("ip:1.1.1.1"));
        assertEquals(2, manager.getActiveCount("ip:1.1.1.1"));

        assertFalse(manager.tryAcquire("ip:1.1.1.1"), "并发数已满且队列无空位时应拒绝");
    }

    @Test
    void isolatesLimitsPerKey() throws InterruptedException {
        RequestQueueManager manager = new RequestQueueManager(newProperties());
        manager.tryAcquire("ip:1.1.1.1");
        manager.tryAcquire("ip:1.1.1.1");

        assertTrue(manager.tryAcquire("ip:2.2.2.2"), "不同 Key 之间不应互相影响");
    }

    @Test
    void releaseFreesConcurrencySlot() throws InterruptedException {
        RequestQueueManager manager = new RequestQueueManager(newProperties());
        manager.tryAcquire("ip:1.1.1.1");
        manager.tryAcquire("ip:1.1.1.1");

        manager.release("ip:1.1.1.1");

        assertEquals(1, manager.getActiveCount("ip:1.1.1.1"));
        assertTrue(manager.tryAcquire("ip:1.1.1.1"), "释放许可后应可以重新获取");
    }

    @Test
    void rejectsAfterQueueTimeout() throws InterruptedException {
        AccessMonitorProperties properties = newProperties();
        properties.getQueue().setMaxConcurrentPerKey(1);
        properties.getQueue().setMaxQueueSizePerKey(1);
        properties.getQueue().setQueueTimeout(Duration.ofMillis(200));
        RequestQueueManager manager = new RequestQueueManager(properties);

        assertTrue(manager.tryAcquire("ip:1.1.1.1"));

        long start = System.nanoTime();
        boolean acquired = manager.tryAcquire("ip:1.1.1.1");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(acquired, "排队等待超时后应拒绝请求");
        assertTrue(elapsedMillis >= 150, "应至少等待接近配置的超时时间，实际: " + elapsedMillis + "ms");
    }

    @Test
    void allowsEverythingWhenDisabled() throws InterruptedException {
        AccessMonitorProperties properties = newProperties();
        properties.getQueue().setEnabled(false);
        RequestQueueManager manager = new RequestQueueManager(properties);

        for (int i = 0; i < 10; i++) {
            assertTrue(manager.tryAcquire("ip:1.1.1.1"), "关闭队列管理后不应限制请求");
        }
    }
}