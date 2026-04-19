package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 速率限制器（Rate Limiter），用于控制单个Key（IP或账户）在单位时间内的请求频率。
 * <p>
 * <strong>核心工作原理：</strong>
 * 采用固定时间窗口算法，以 1 分钟为一个统计周期。
 * 对每个Key维护一个 {@link AccessRecord} 实例，记录其在当前窗口内的请求次数。
 * 当请求次数超过配置阈值 {@code maxRequestsPerMinute} 时，立即对该Key执行封禁（Ban），
 * 在 {@code banDuration} 时长内拒绝其所有后续请求，返回 HTTP 429（Too Many Requests）。
 * </p>
 * <p>
 * <strong>时间窗口管理：</strong>
 * 每个 {@code AccessRecord} 都有 {@code firstRequestTime} 标记窗口起点。
 * 当新请求到达时，首先检查窗口是否已过期（超过1分钟），若已过期则调用 {@link AccessRecord#reset()} 重置计数，
 * 开始新的统计周期。这种实现方式简单高效，但在窗口边界处可能存在少量计数翻倍的问题（如窗口最后一秒和下一秒），
 * 适用于绝大多数Web场景的限流需求。如需更精确的限流，可考虑升级为滑动窗口或令牌桶算法。
 * </p>
 * <p>
 * <strong>线程安全：</strong>
 * 使用 {@link ConcurrentHashMap} 存储各Key的 {@code AccessRecord}，保证并发读写安全。
 * 在对单个 {@code AccessRecord} 进行复合操作（检查封禁状态、检查窗口过期、计数自增）时，
 * 使用 {@code synchronized (record)} 进行细粒度加锁，确保同一Key的操作串行化，同时不同Key之间互不阻塞。
 * </p>
 * <p>
 * <strong>内存清理：</strong>
 * 通过定时任务每1分钟扫描一次 {@code records} 映射表，移除那些窗口已过期超过5分钟且未被封禁的记录，
 * 防止长期运行的系统中内存持续增长。
 * </p>
 */
@Component
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /**
     * 固定的时间窗口长度，当前实现为 1 分钟。
     * 所有Key的请求频率均以此为周期进行统计。
     */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * 限流器的配置属性，包含最大请求数、封禁时长、白名单、黑名单、限流模式等参数。
     */
    private final AccessMonitorProperties properties;

    /**
     * 存储所有Key（IP或账户）的访问记录映射表。
     * Key为限流标识（如 {@code "ip:192.168.1.1"}），Value为该标识的详细访问统计。
     * 使用 {@link ConcurrentHashMap} 保证高并发场景下的线程安全。
     */
    private final Map<String, AccessRecord> records = new ConcurrentHashMap<>();

    /**
     * 后台清理任务的执行器，用于定期删除过期的访问记录，释放内存。
     */
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * 构造方法，初始化速率限制器并启动后台清理任务。
     *
     * @param properties 限流器的配置属性
     */
    public RateLimiter(AccessMonitorProperties properties) {
        this.properties = properties;
        startCleanupTask();
    }

    /**
     * 判断指定Key的当前请求是否被允许通过。
     * <p>
     * 执行流程如下：
     * <ol>
     *   <li><strong>功能开关检查：</strong> 如果限流功能被禁用，直接放行。</li>
     *   <li><strong>白名单检查：</strong> 如果Key在白名单中，直接放行。</li>
     *   <li><strong>黑名单检查：</strong> 如果Key在黑名单中，直接拒绝。</li>
     *   <li><strong>获取或创建记录：</strong> 从 {@code records} 中获取该Key的 {@code AccessRecord}，
     *       不存在时通过 {@code computeIfAbsent} 自动创建。</li>
     *   <li><strong>同步检查：</strong> 对 {@code AccessRecord} 实例加锁（{@code synchronized}），
     *       确保同一Key的并发请求串行处理。</li>
     *   <li><strong>封禁状态检查：</strong> 调用 {@link AccessRecord#isBanned()}，若已被封禁则拒绝。</li>
     *   <li><strong>窗口过期检查：</strong> 调用 {@link AccessRecord#isWindowExpired(Duration)}，
     *       若已过期则调用 {@link AccessRecord#reset()} 重置计数，开始新窗口。</li>
     *   <li><strong>计数自增与阈值判断：</strong> 调用 {@link AccessRecord#incrementAndGet()}，
     *       若超过 {@code maxRequestsPerMinute} 则执行封禁（{@link AccessRecord#ban(Duration)}）并拒绝。</li>
     *   <li><strong>放行：</strong> 请求未超限，允许通过。</li>
     * </ol>
     * </p>
     *
     * @param key 限流标识，格式如 {@code "ip:192.168.1.1"} 或 {@code "acct:zhangsan"}
     * @return {@code true} 表示请求被允许通过，{@code false} 表示请求被拒绝
     */
    public boolean isAllowed(String key) {
        // 步骤1：功能开关检查。如果限流功能在配置中被关闭，直接放行所有请求。
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }

        // 步骤2：白名单检查。白名单中的Key不受任何限流约束，通常用于内部服务或监控探针。
        if (properties.getRateLimit().getWhitelist().contains(key)) {
            return true;
        }

        // 步骤3：黑名单检查。黑名单中的Key会被无条件拒绝，即使请求频次很低。
        if (properties.getRateLimit().getBlacklist().contains(key)) {
            logger.warn("黑名单中的Key已被拦截: {}", key);
            return false;
        }

        // 步骤4：获取或创建该Key的访问记录。使用 ConcurrentHashMap 的 computeIfAbsent 保证线程安全。
        AccessRecord record = records.computeIfAbsent(key, AccessRecord::new);

        // 步骤5-8：对单个记录加锁进行复合操作。
        // 注意：此处使用 synchronized(record) 而非 synchronized(this)，
        // 目的是实现细粒度锁，只锁定当前Key对应的记录，不影响其他Key的并发处理。
        synchronized (record) {
            // 检查封禁状态。如果仍在封禁期内，直接拒绝。
            if (record.isBanned()) {
                logger.warn("已被封禁的Key拒绝访问: {}, 封禁截止时间: {}", key, record.getBannedUntil());
                return false;
            }

            // 检查当前统计窗口是否已过期（超过1分钟）。
            // 如果过期，重置计数和时间戳，开始新的统计周期。
            if (record.isWindowExpired(WINDOW)) {
                record.reset();
            }

            // 原子地将请求计数加1，并获取最新值。
            int count = record.incrementAndGet();

            // 判断当前计数是否超过配置阈值。
            if (count > properties.getRateLimit().getMaxRequestsPerMinute()) {
                // 超过阈值，执行封禁操作，设置封禁截止时间。
                record.ban(properties.getRateLimit().getBanDuration());
                logger.warn("速率超限触发封禁 - Key: {}, 当前计数: {}, 封禁时长: {}",
                    key, count, properties.getRateLimit().getBanDuration());
                return false;
            }

            // 请求未超限，允许通过。
            return true;
        }
    }

    /**
     * 根据Key获取对应的访问记录。
     * <p>
     * 主要用于监控端点查询特定Key的实时状态，如当前计数、封禁状态、封禁截止时间等。
     * </p>
     *
     * @param key 限流标识
     * @return 对应的 {@link AccessRecord}，如果不存在则返回 {@code null}
     */
    public AccessRecord getRecord(String key) {
        return records.get(key);
    }

    /**
     * 获取当前所有活跃访问记录的副本。
     * <p>
     * 返回的是 {@link ConcurrentHashMap} 的快照副本，对返回的 Map 进行修改不会影响内部状态。
     * 主要用于监控端点（如 {@code /actuator/accessmonitor}）展示全局限流概览。
     * </p>
     *
     * @return 包含所有Key及其访问记录的 Map 副本
     */
    public Map<String, AccessRecord> getAllRecords() {
        return new ConcurrentHashMap<>(records);
    }

    /**
     * 启动后台清理任务。
     * <p>
     * 使用 {@link ScheduledExecutorService} 每1分钟执行一次清理：
     * 遍历 {@code records} 中的所有条目，移除那些<strong>未被封禁</strong>且<strong>窗口已过期超过5分钟</strong>的记录。
     * 这样可以避免长时间运行的系统中，因大量历史Key的访问记录而导致内存泄漏。
     * </p>
     * <p>
     * 被封禁的记录不会被清理，因为封禁状态需要持续生效，直到封禁时间到期后由 {@link AccessRecord#isBanned()} 自动清除。
     * </p>
     */
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                // 清理条件：未被封禁 且 窗口已过期超过5分钟。
                // 保留被封禁的记录，因为封禁状态必须持续生效到 banUntil。
                records.entrySet().removeIf(entry -> {
                    AccessRecord record = entry.getValue();
                    return !record.isBanned() && record.isWindowExpired(Duration.ofMinutes(5));
                });
            } catch (Exception e) {
                logger.error("限流器后台清理任务执行异常", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
}
