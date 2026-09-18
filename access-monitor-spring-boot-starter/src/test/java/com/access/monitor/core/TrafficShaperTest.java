package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶流量整形器测试。
 * <p>
 * 令牌补充速率设为 0，使桶容量在测试期间保持不变，从而让断言完全确定。
 * </p>
 */
class TrafficShaperTest {

    private AccessMonitorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AccessMonitorProperties();
        properties.getTrafficShaper().setEnabled(true);
        properties.getTrafficShaper().setGlobalRatePerSecond(0);
        properties.getTrafficShaper().setBurstCapacity(3);
    }

    @Test
    void allowsRequestsUntilBurstCapacityIsExhausted() {
        TrafficShaper shaper = new TrafficShaper(properties);

        assertTrue(shaper.tryEnter(), "第 1 个令牌应可用");
        assertTrue(shaper.tryEnter(), "第 2 个令牌应可用");
        assertTrue(shaper.tryEnter(), "第 3 个令牌应可用");
        assertFalse(shaper.tryEnter(), "桶空后应拒绝请求");
        assertEquals(0, shaper.getAvailablePermits());
    }

    @Test
    void returnsPermitOnLeave() {
        TrafficShaper shaper = new TrafficShaper(properties);
        for (int i = 0; i < 3; i++) {
            shaper.tryEnter();
        }

        shaper.leave();

        assertEquals(1, shaper.getAvailablePermits());
        assertTrue(shaper.tryEnter(), "归还令牌后应重新允许请求");
    }

    @Test
    void allowsEverythingWhenDisabled() {
        properties.getTrafficShaper().setEnabled(false);
        TrafficShaper shaper = new TrafficShaper(properties);

        for (int i = 0; i < 10; i++) {
            assertTrue(shaper.tryEnter(), "关闭流量整形后不应限制请求");
        }
    }

    @Test
    void matchesPrioritizedPathByPrefix() {
        properties.getTrafficShaper().setPrioritizedPaths(List.of("/actuator", "/health"));
        TrafficShaper shaper = new TrafficShaper(properties);

        assertTrue(shaper.isPrioritizedPath("/actuator/accessmonitor"));
        assertTrue(shaper.isPrioritizedPath("/health/liveness"));
        assertFalse(shaper.isPrioritizedPath("/api/orders"));
        assertFalse(shaper.isPrioritizedPath(null), "空路径不应被判定为优先路径");
    }
}