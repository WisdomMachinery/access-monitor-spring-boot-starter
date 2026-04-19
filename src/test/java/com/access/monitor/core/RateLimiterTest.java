package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private AccessMonitorProperties properties;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        properties = new AccessMonitorProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setMaxRequestsPerMinute(20);
        properties.getRateLimit().setBanDuration(Duration.ofMinutes(10));
        rateLimiter = new RateLimiter(properties);
    }

    @Test
    void testAllowedUnderLimit() {
        String key = "ip:192.168.1.1";
        for (int i = 0; i < 20; i++) {
            assertTrue(rateLimiter.isAllowed(key), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testBlockedOverLimit() {
        String key = "ip:192.168.1.1";
        for (int i = 0; i < 20; i++) {
            rateLimiter.isAllowed(key);
        }
        assertFalse(rateLimiter.isAllowed(key), "Request 21 should be blocked");
    }

    @Test
    void testWhitelist() {
        properties.getRateLimit().getWhitelist().add("ip:192.168.1.1");
        String key = "ip:192.168.1.1";
        for (int i = 0; i < 25; i++) {
            assertTrue(rateLimiter.isAllowed(key), "Whitelisted IP should not be blocked");
        }
    }
}
