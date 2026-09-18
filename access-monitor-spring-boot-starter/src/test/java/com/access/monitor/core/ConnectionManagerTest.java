package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接数管理器测试，覆盖全局上限、单 IP 上限、配额释放和开关降级。
 */
class ConnectionManagerTest {

    @Test
    void rejectsWhenGlobalLimitReached() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getConnectionLimit().setEnabled(true);
        properties.getConnectionLimit().setGlobalMaxConnections(2);
        properties.getConnectionLimit().setMaxConnectionsPerIp(10);
        ConnectionManager manager = new ConnectionManager(properties);

        assertTrue(manager.register("c1", "ip1"));
        assertTrue(manager.register("c2", "ip2"));
        assertEquals(2, manager.getGlobalActiveCount());

        assertFalse(manager.register("c3", "ip3"), "超过全局连接上限应拒绝");
    }

    @Test
    void rejectsWhenPerIpLimitReached() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getConnectionLimit().setEnabled(true);
        properties.getConnectionLimit().setGlobalMaxConnections(100);
        properties.getConnectionLimit().setMaxConnectionsPerIp(1);
        ConnectionManager manager = new ConnectionManager(properties);

        assertTrue(manager.register("c1", "ip1"));
        assertFalse(manager.register("c2", "ip1"), "同一 IP 超过单 IP 上限应拒绝");
        assertTrue(manager.register("c3", "ip2"), "其他 IP 不应受影响");
        assertEquals(1, manager.getIpActiveCount("ip1"));
    }

    @Test
    void unregisterReleasesQuota() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getConnectionLimit().setEnabled(true);
        properties.getConnectionLimit().setGlobalMaxConnections(1);
        properties.getConnectionLimit().setMaxConnectionsPerIp(1);
        ConnectionManager manager = new ConnectionManager(properties);

        assertTrue(manager.register("c1", "ip1"));
        assertFalse(manager.register("c2", "ip1"));

        manager.unregister("c1", "ip1");

        assertEquals(0, manager.getGlobalActiveCount());
        assertEquals(0, manager.getIpActiveCount("ip1"));
        assertTrue(manager.register("c2", "ip1"), "释放配额后应允许新连接");
    }

    @Test
    void allowsEverythingWhenDisabled() {
        AccessMonitorProperties properties = new AccessMonitorProperties();
        properties.getConnectionLimit().setEnabled(false);
        properties.getConnectionLimit().setGlobalMaxConnections(0);
        ConnectionManager manager = new ConnectionManager(properties);

        assertTrue(manager.register("c1", "ip1"), "关闭连接数限制后不应拒绝连接");
    }
}
