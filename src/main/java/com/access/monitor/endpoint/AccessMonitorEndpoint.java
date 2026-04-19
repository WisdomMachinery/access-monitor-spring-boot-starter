package com.access.monitor.endpoint;

import com.access.monitor.core.*;
import com.access.monitor.properties.AccessMonitorProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 访问监控的 Actuator 管理端点（Management Endpoint）。
 * <p>
 * 该类利用 Spring Boot Actuator 的 {@link Endpoint} 机制，暴露一组 RESTful 接口，
 * 供运维人员和开发者实时查询访问监控模块的运行状态。
 * 端点ID为 {@code "accessmonitor"}，因此完整的路径为 {@code /actuator/accessmonitor}。
 * </p>
 * <p>
 * <strong>可访问的端点路径：</strong>
 * <ul>
 *   <li>{@code GET /actuator/accessmonitor} - 返回访问监控模块的整体状态概览。</li>
 *   <li>{@code GET /actuator/accessmonitor/rateLimitStatus} - 返回速率限制的详细状态，
 *       包括当前活跃记录数、被封禁的Key列表及其解封时间。</li>
 *   <li>{@code GET /actuator/accessmonitor/queueStatus} - 返回请求队列的配置信息和当前状态。</li>
 *   <li>{@code GET /actuator/accessmonitor/trafficShaperStatus} - 返回流量整形器的配置和当前令牌余量。</li>
 *   <li>{@code GET /actuator/accessmonitor/slowRequestStatus} - 返回慢请求检测的配置和当前被跟踪的请求列表。</li>
 *   <li>{@code GET /actuator/accessmonitor/connectionStatus} - 返回连接数限制的配置和当前全局连接数。</li>
 *   <li>{@code GET /actuator/accessmonitor/record/{key}} - 查询特定Key（如 {@code "ip:192.168.1.1"}）
 *       的详细访问记录，包括当前计数、封禁状态等。</li>
 * </ul>
 * </p>
 * <p>
 * <strong>安全提示：</strong>
 * Actuator 端点可能暴露敏感的系统运行信息（如被封禁的IP列表、当前连接数等），
 * 在生产环境中应确保这些端点受到适当的访问控制（如通过 Spring Security 限制仅管理员可访问），
 * 或者将其配置为不暴露到公网（通过 management.server.port 设置独立的管理端口，并限制网络访问）。
 * </p>
 */
@Component
@Endpoint(id = "accessmonitor")
public class AccessMonitorEndpoint {

    /** 访问监控模块的配置属性，用于返回配置信息。 */
    private final AccessMonitorProperties properties;
    /** 速率限制器，用于查询限流状态和被封禁的Key。 */
    private final RateLimiter rateLimiter;
    /** 请求队列管理器，用于查询队列状态。 */
    private final RequestQueueManager queueManager;
    /** 流量整形器，用于查询令牌桶余量。 */
    private final TrafficShaper trafficShaper;
    /** 慢请求检测器，用于查询当前正在跟踪的请求列表。 */
    private final SlowRequestDetector slowRequestDetector;
    /** 连接管理器，用于查询当前连接数。 */
    private final ConnectionManager connectionManager;

    /**
     * 构造方法，由 Spring 容器注入所有依赖组件。
     */
    public AccessMonitorEndpoint(AccessMonitorProperties properties,
                                  RateLimiter rateLimiter,
                                  RequestQueueManager queueManager,
                                  TrafficShaper trafficShaper,
                                  SlowRequestDetector slowRequestDetector,
                                  ConnectionManager connectionManager) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.queueManager = queueManager;
        this.trafficShaper = trafficShaper;
        this.slowRequestDetector = slowRequestDetector;
        this.connectionManager = connectionManager;
    }

    /**
     * 获取访问监控模块的整体状态概览。
     * <p>
     * 该方法聚合了所有子系统的状态信息，返回一个包含全局开关、限流状态、队列状态、
     * 流量整形状态、慢请求状态和连接状态的综合视图。
     * </p>
     *
     * @return 包含所有子系统状态的 Map
     */
    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("rateLimit", rateLimitStatus());
        status.put("queue", queueStatus());
        status.put("trafficShaper", trafficShaperStatus());
        status.put("slowRequests", slowRequestStatus());
        status.put("connections", connectionStatus());
        return status;
    }

    /**
     * 获取速率限制器的详细状态。
     * <p>
     * 返回的信息包括：
     * <ul>
     *   <li>限流功能是否启用</li>
     *   <li>当前限流模式（IP/ACCOUNT/BOTH）</li>
     *   <li>每分钟最大请求数阈值</li>
     *   <li>封禁时长</li>
     *   <li>当前活跃的访问记录总数</li>
     *   <li>当前被封禁的Key列表及其解封时间</li>
     * </ul>
     * </p>
     *
     * @return 速率限制状态 Map
     */
    @ReadOperation
    public Map<String, Object> rateLimitStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getRateLimit().isEnabled());
        status.put("mode", properties.getRateLimit().getMode());
        status.put("maxRequestsPerMinute", properties.getRateLimit().getMaxRequestsPerMinute());
        status.put("banDuration", properties.getRateLimit().getBanDuration().toString());
        status.put("activeRecords", rateLimiter.getAllRecords().size());
        // 过滤出所有被封禁的记录，转换为 Key → 解封时间 的映射。
        status.put("bannedKeys", rateLimiter.getAllRecords().entrySet().stream()
            .filter(e -> e.getValue().isBanned())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().getBannedUntil().toString()
            )));
        return status;
    }

    /**
     * 获取请求队列的状态信息。
     * <p>
     * 返回的信息包括队列功能是否启用、每个Key的最大并发数、队列最大长度等配置参数。
     * </p>
     *
     * @return 队列状态 Map
     */
    @ReadOperation
    public Map<String, Object> queueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getQueue().isEnabled());
        status.put("maxConcurrentPerKey", properties.getQueue().getMaxConcurrentPerKey());
        status.put("maxQueueSizePerKey", properties.getQueue().getMaxQueueSizePerKey());
        return status;
    }

    /**
     * 获取流量整形器的状态信息。
     * <p>
     * 返回的信息包括流量整形是否启用、全局每秒速率、桶容量，以及当前令牌桶中剩余的令牌数量。
     * 通过观察 {@code availablePermits}，运维人员可以直观了解系统当前的流量余量。
     * </p>
     *
     * @return 流量整形状态 Map
     */
    @ReadOperation
    public Map<String, Object> trafficShaperStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getTrafficShaper().isEnabled());
        status.put("globalRatePerSecond", properties.getTrafficShaper().getGlobalRatePerSecond());
        status.put("burstCapacity", properties.getTrafficShaper().getBurstCapacity());
        status.put("availablePermits", trafficShaper.getAvailablePermits());
        return status;
    }

    /**
     * 获取慢请求检测的状态信息。
     * <p>
     * 返回的信息包括慢请求检测是否启用、慢请求阈值、挂死请求阈值，
     * 以及当前所有正在被跟踪的请求列表（包含每个请求的IP、URI和已耗时时长）。
     * </p>
     *
     * @return 慢请求检测状态 Map
     */
    @ReadOperation
    public Map<String, Object> slowRequestStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getSlowRequest().isEnabled());
        status.put("threshold", properties.getSlowRequest().getThreshold().toString());
        status.put("hangThreshold", properties.getSlowRequest().getHangThreshold().toString());
        status.put("activeTrackedRequests", slowRequestDetector.getActiveRequests().size());
        // 将所有被跟踪的请求转换为详细的可读信息。
        status.put("requests", slowRequestDetector.getActiveRequests().entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> {
                    SlowRequestDetector.TrackedRequest req = e.getValue();
                    Map<String, Object> info = new HashMap<>();
                    info.put("ip", req.ip);
                    info.put("uri", req.uri);
                    info.put("elapsedMs", java.time.Duration.between(req.startTime, Instant.now()).toMillis());
                    return info;
                }
            )));
        return status;
    }

    /**
     * 获取连接数限制的状态信息。
     * <p>
     * 返回的信息包括连接数限制是否启用、全局最大连接数、单IP最大连接数，
     * 以及当前系统全局的活动连接总数。
     * </p>
     *
     * @return 连接数限制状态 Map
     */
    @ReadOperation
    public Map<String, Object> connectionStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getConnectionLimit().isEnabled());
        status.put("globalMaxConnections", properties.getConnectionLimit().getGlobalMaxConnections());
        status.put("maxConnectionsPerIp", properties.getConnectionLimit().getMaxConnectionsPerIp());
        status.put("globalActive", connectionManager.getGlobalActiveCount());
        return status;
    }

    /**
     * 查询特定Key的详细访问记录。
     * <p>
     * 通过路径参数传入Key（如 {@code "ip:192.168.1.1"} 或 {@code "acct:zhangsan"}），
     * 返回该Key对应的 {@link AccessRecord} 中的详细信息，包括当前请求计数、是否被封禁、
     * 封禁截止时间、窗口起始时间等。
     * </p>
     *
     * @param key 限流标识Key
     * @return 该Key的详细访问记录，如果Key不存在则返回错误提示
     */
    @ReadOperation
    public Map<String, Object> record(@Selector String key) {
        Map<String, Object> result = new HashMap<>();
        AccessRecord record = rateLimiter.getRecord(key);
        if (record != null) {
            result.put("key", record.getKey());
            result.put("requestCount", record.getRequestCount());
            result.put("banned", record.isBanned());
            if (record.getBannedUntil() != null) {
                result.put("bannedUntil", record.getBannedUntil().toString());
            }
            result.put("firstRequestTime", record.getFirstRequestTime().toString());
        } else {
            result.put("error", "未找到该Key的访问记录");
        }
        return result;
    }
}
