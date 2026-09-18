package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 慢请求检测器测试。
 * <p>
 * 阈值设置为 1 小时以上，确保定时检查任务在测试期间不会触发。
 * </p>
 */
class SlowRequestDetectorTest {

    private AccessMonitorProperties newProperties() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getSlowRequest().setEnabled(true);
        properties.getSlowRequest().setThreshold(Duration.ofHours(1));
        properties.getSlowRequest().setHangThreshold(Duration.ofHours(2));
        properties.getSlowRequest().setAutoInterrupt(false);
        return properties;
    }

    @Test
    void tracksAndCompletesRequest() {
        SlowRequestDetector detector = new SlowRequestDetector(newProperties());

        detector.track("req-1", "1.1.1.1", "/api/orders", Thread.currentThread());

        assertEquals(1, detector.getActiveRequests().size());
        assertTrue(detector.getActiveRequests().containsKey("req-1"));

        detector.complete("req-1");

        assertTrue(detector.getActiveRequests().isEmpty(), "请求完成后应停止跟踪");
    }

    @Test
    void completingUnknownRequestIsIgnored() {
        SlowRequestDetector detector = new SlowRequestDetector(newProperties());

        detector.complete("not-tracked");

        assertTrue(detector.getActiveRequests().isEmpty());
    }

    @Test
    void tracksNothingWhenDisabled() {
        AccessMonitorProperties properties = newProperties();
        properties.getSlowRequest().setEnabled(false);
        SlowRequestDetector detector = new SlowRequestDetector(properties);

        detector.track("req-1", "1.1.1.1", "/api/orders", Thread.currentThread());

        assertTrue(detector.getActiveRequests().isEmpty());
        assertFalse(properties.getSlowRequest().isEnabled());
    }
}
