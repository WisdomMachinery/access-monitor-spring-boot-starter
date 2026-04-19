package com.access.monitor.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问监控模块的配置属性类。
 * <p>
 * 该类通过 {@code @ConfigurationProperties} 注解与 Spring Boot 的配置文件（如 application.yml）进行绑定，
 * 所有配置项的前缀为 {@code access.monitor}。
 * 开发者可以通过修改配置文件，无需改动代码即可调整限流、队列、流量整形、慢请求检测等各项参数。
 * </p>
 *
 * <p>配置示例（application.yml）：</p>
 * <pre>
 * access:
 *   monitor:
 *     enabled: true
 *     rate-limit:
 *       max-requests-per-minute: 20
 *       ban-duration: 10m
 * </pre>
 */
@ConfigurationProperties(prefix = "access.monitor")
public class AccessMonitorProperties {

    /**
     * 全局开关，控制整个访问监控模块是否启用。
     * 默认值为 {@code true}，即默认开启。
     * 设置为 {@code false} 时，所有监控和限流逻辑将完全失效。
     */
    private boolean enabled = true;

    /**
     * 速率限制相关配置，用于控制单个IP或账户在单位时间内的最大请求次数。
     * 包含白名单、黑名单、封禁时长等子配置。
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * 请求队列相关配置，用于对超出并发上限的请求进行排队管理。
     * 可防止瞬时高并发冲垮后端服务。
     */
    private Queue queue = new Queue();

    /**
     * 流量整形相关配置，采用令牌桶算法对全系统的请求速率进行平滑控制。
     * 适合应对突发流量和全局限流场景。
     */
    private TrafficShaper trafficShaper = new TrafficShaper();

    /**
     * 慢请求检测相关配置，用于识别并处理响应时间过长或已挂死的请求。
     * 支持自动中断长时间无响应的处理线程，释放服务器资源。
     */
    private SlowRequestDetector slowRequest = new SlowRequestDetector();

    /**
     * 连接数限制相关配置，用于控制单个IP和全局的最大并发连接数。
     * 同时支持空闲连接的超时自动清理，防止僵尸连接占用资源。
     */
    private ConnectionLimit connectionLimit = new ConnectionLimit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Queue getQueue() {
        return queue;
    }

    public void setQueue(Queue queue) {
        this.queue = queue;
    }

    public TrafficShaper getTrafficShaper() {
        return trafficShaper;
    }

    public void setTrafficShaper(TrafficShaper trafficShaper) {
        this.trafficShaper = trafficShaper;
    }

    public SlowRequestDetector getSlowRequest() {
        return slowRequest;
    }

    public void setSlowRequest(SlowRequestDetector slowRequest) {
        this.slowRequest = slowRequest;
    }

    public ConnectionLimit getConnectionLimit() {
        return connectionLimit;
    }

    public void setConnectionLimit(ConnectionLimit connectionLimit) {
        this.connectionLimit = connectionLimit;
    }

    /**
     * 速率限制（Rate Limiting）的详细配置子类。
     * <p>
     * 核心逻辑：在固定时间窗口内（默认1分钟），统计每个Key（IP或账户）的请求次数，
     * 一旦超过阈值 {@code maxRequestsPerMinute}，则立即对该Key执行封禁（Ban），
     * 在 {@code banDuration} 时间内拒绝其所有请求，以此防御暴力请求和爬虫攻击。
     * </p>
     */
    public static class RateLimit {

        /**
         * 速率限制功能的独立开关。
         * 默认开启。即使全局 {@code enabled} 为 true，此项为 false 时也不会触发限流逻辑。
         */
        private boolean enabled = true;

        /**
         * 每个Key（IP或账户）在1分钟时间窗口内允许的最大请求次数。
         * 默认值为 20，即单IP/账户每分钟最多允许 20 次请求。
         * 第 21 次请求将触发封禁。
         */
        private int maxRequestsPerMinute = 20;

        /**
         * 触发速率限制后的封禁时长。
         * 默认值为 10 分钟（Duration.ofMinutes(10)）。
         * 封禁期间，该Key的所有请求将直接返回 429（Too Many Requests）。
         */
        private Duration banDuration = Duration.ofMinutes(10);

        /**
         * 白名单列表，列入其中的IP或账户不受速率限制约束。
         * 常用于放过内部服务地址、测试环境IP、监控探针等可信来源。
         */
        private List<String> whitelist = new ArrayList<>();

        /**
         * 黑名单列表，列入其中的IP或账户将被无条件拒绝访问。
         * 即使请求频次很低，也会直接拦截，适用于已知恶意IP的封禁。
         */
        private List<String> blacklist = new ArrayList<>();

        /**
         * 限流的统计维度模式。
         * <ul>
         *   <li>{@code IP} - 按客户端IP地址限流（默认）</li>
         *   <li>{@code ACCOUNT} - 按登录账户限流（需应用层传递账户标识）</li>
         *   <li>{@code BOTH} - 优先按账户限流，无账户信息时回退到IP</li>
         * </ul>
         */
        private Mode mode = Mode.IP;

        /**
         * 限流模式的枚举定义。
         */
        public enum Mode {
            /** 仅按IP地址进行限流统计。 */
            IP,
            /** 仅按账户标识进行限流统计。 */
            ACCOUNT,
            /** 优先账户，无账户信息时按IP限流。 */
            BOTH
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }

        public Duration getBanDuration() {
            return banDuration;
        }

        public void setBanDuration(Duration banDuration) {
            this.banDuration = banDuration;
        }

        public List<String> getWhitelist() {
            return whitelist;
        }

        public void setWhitelist(List<String> whitelist) {
            this.whitelist = whitelist;
        }

        public List<String> getBlacklist() {
            return blacklist;
        }

        public void setBlacklist(List<String> blacklist) {
            this.blacklist = blacklist;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }
    }

    /**
     * 请求队列（Request Queue）的详细配置子类。
     * <p>
     * 核心逻辑：当同一Key（IP或账户）的并发请求数超过 {@code maxConcurrentPerKey} 时，
     * 后续请求不会立即被拒绝，而是进入一个阻塞队列进行等待。
     * 如果队列已满或等待超时，则返回 503（Service Unavailable）。
     * 这种机制能有效削峰填谷，避免瞬时高并发直接压垮服务。
     * </p>
     */
    public static class Queue {

        /**
         * 请求队列功能的独立开关。
         * 默认开启。
         */
        private boolean enabled = true;

        /**
         * 每个Key对应的最大队列长度。
         * 默认值为 100，当同一IP/账户已有 10 个请求正在处理、队列中已有 100 个请求在等待时，
         * 第 111 个请求将被直接拒绝。
         */
        private int maxQueueSizePerKey = 100;

        /**
         * 每个Key允许的最大并发处理数。
         * 默认值为 10，即同一IP/账户最多同时处理 10 个请求，超出的请求进入队列排队。
         */
        private int maxConcurrentPerKey = 10;

        /**
         * 请求在队列中的最长等待时间。
         * 默认值为 30 秒。超过此时间仍未获得执行机会的请求将被移除并返回 503。
         */
        private Duration queueTimeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxQueueSizePerKey() {
            return maxQueueSizePerKey;
        }

        public void setMaxQueueSizePerKey(int maxQueueSizePerKey) {
            this.maxQueueSizePerKey = maxQueueSizePerKey;
        }

        public int getMaxConcurrentPerKey() {
            return maxConcurrentPerKey;
        }

        public void setMaxConcurrentPerKey(int maxConcurrentPerKey) {
            this.maxConcurrentPerKey = maxConcurrentPerKey;
        }

        public Duration getQueueTimeout() {
            return queueTimeout;
        }

        public void setQueueTimeout(Duration queueTimeout) {
            this.queueTimeout = queueTimeout;
        }
    }

    /**
     * 流量整形（Traffic Shaping）的详细配置子类。
     * <p>
     * 核心逻辑：采用令牌桶（Token Bucket）算法，维护一个全局的令牌池。
     * 每秒以固定速率 {@code globalRatePerSecond} 向桶中添加令牌，桶的容量上限为 {@code burstCapacity}。
     * 每个非优先请求需要消耗一个令牌才能通过；令牌不足时请求会被阻塞或拒绝。
     * 该机制用于平滑突发流量，防止系统在短时间内被大量请求冲垮。
     * </p>
     */
    public static class TrafficShaper {

        /**
         * 流量整形功能的独立开关。
         * 默认开启。
         */
        private boolean enabled = true;

        /**
         * 全局每秒产生的令牌数，即系统的平均处理速率上限。
         * 默认值为 1000，表示系统平均每秒最多处理 1000 个请求。
         */
        private int globalRatePerSecond = 1000;

        /**
         * 令牌桶的容量上限，即系统允许的最大突发流量。
         * 默认值为 2000，表示即使瞬间涌入 2000 个请求，也能一次性处理（消耗完桶内所有令牌）。
         */
        private int burstCapacity = 2000;

        /**
         * 优先路径列表，匹配到的请求将绕过流量整形，直接放行。
         * 默认包含健康检查等路径，确保在系统高负载时探针仍能正常访问。
         * 配置时使用路径前缀匹配，例如 {@code /health} 会匹配 {@code /health} 及其子路径。
         */
        private List<String> prioritizedPaths = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getGlobalRatePerSecond() {
            return globalRatePerSecond;
        }

        public void setGlobalRatePerSecond(int globalRatePerSecond) {
            this.globalRatePerSecond = globalRatePerSecond;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public List<String> getPrioritizedPaths() {
            return prioritizedPaths;
        }

        public void setPrioritizedPaths(List<String> prioritizedPaths) {
            this.prioritizedPaths = prioritizedPaths;
        }
    }

    /**
     * 慢请求检测（Slow Request Detection）的详细配置子类。
     * <p>
     * 核心逻辑：为每个进入系统的请求建立跟踪记录，启动定时任务分别在
     * {@code threshold} 和 {@code hangThreshold} 两个时间点进行检查。
     * 如果请求在 threshold 时间后仍未完成，记录 WARN 级别日志；
     * 如果在 hangThreshold 时间后仍未完成，记录 ERROR 级别日志，并可选择自动中断处理线程。
     * 该机制用于发现和防御慢连接攻击（Slowloris）以及定位性能瓶颈。
     * </p>
     */
    public static class SlowRequestDetector {

        /**
         * 慢请求检测功能的独立开关。
         * 默认开启。
         */
        private boolean enabled = true;

        /**
         * 慢请求判定阈值。
         * 默认值为 10 秒。请求处理时间超过此值将被标记为慢请求并输出警告日志。
         */
        private Duration threshold = Duration.ofSeconds(10);

        /**
         * 挂死请求判定阈值。
         * 默认值为 60 秒。请求处理时间超过此值将被判定为挂死/僵尸请求，输出错误日志。
         */
        private Duration hangThreshold = Duration.ofSeconds(60);

        /**
         * 是否自动中断挂死请求的处理线程。
         * 默认值为 {@code true}。当请求超过 {@code hangThreshold} 时，
         * 向处理线程发送 {@link Thread#interrupt()} 信号，促使其尽快结束并释放资源。
         * <p><strong>注意：</strong> 被中断的线程需要正确处理 {@link InterruptedException}，
         * 否则中断信号可能被忽略，导致资源仍无法释放。</p>
         */
        private boolean autoInterrupt = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getThreshold() {
            return threshold;
        }

        public void setThreshold(Duration threshold) {
            this.threshold = threshold;
        }

        public Duration getHangThreshold() {
            return hangThreshold;
        }

        public void setHangThreshold(Duration hangThreshold) {
            this.hangThreshold = hangThreshold;
        }

        public boolean isAutoInterrupt() {
            return autoInterrupt;
        }

        public void setAutoInterrupt(boolean autoInterrupt) {
            this.autoInterrupt = autoInterrupt;
        }
    }

    /**
     * 连接数限制（Connection Limit）的详细配置子类。
     * <p>
     * 核心逻辑：在请求进入系统时进行连接登记，在请求结束时进行注销。
     * 同时维护单IP连接数和全局连接数两个维度的计数器。
     * 当任一维度达到上限时，新连接将被拒绝（返回 503）。
     * 此外，通过定时任务清理超过 {@code idleTimeout} 未活动的连接，防止僵尸连接长期占用配额。
     * </p>
     */
    public static class ConnectionLimit {

        /**
         * 连接数限制功能的独立开关。
         * 默认开启。
         */
        private boolean enabled = true;

        /**
         * 单个IP地址允许的最大并发连接数。
         * 默认值为 50。该值需根据业务场景合理设置：
         * 过小可能导致正常用户被误拦截（特别是NAT后的企业内网出口IP），
         * 过大则无法有效防御单IP的大量连接攻击。
         */
        private int maxConnectionsPerIp = 50;

        /**
         * 系统全局允许的最大并发连接数。
         * 默认值为 10000。当系统总连接数达到此上限时，任何新连接都将被拒绝。
         * 该值应根据服务器的文件描述符上限、内存容量等实际资源情况设定。
         */
        private int globalMaxConnections = 10000;

        /**
         * 连接的空闲超时时间。
         * 默认值为 5 分钟。超过此时间未发送新请求的连接将被视为僵尸连接并强制清理。
         * 清理动作不会直接断开TCP连接，但会从内部计数器中移除，释放配额供新连接使用。
         */
        private Duration idleTimeout = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxConnectionsPerIp() {
            return maxConnectionsPerIp;
        }

        public void setMaxConnectionsPerIp(int maxConnectionsPerIp) {
            this.maxConnectionsPerIp = maxConnectionsPerIp;
        }

        public int getGlobalMaxConnections() {
            return globalMaxConnections;
        }

        public void setGlobalMaxConnections(int globalMaxConnections) {
            this.globalMaxConnections = globalMaxConnections;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
    }
}
