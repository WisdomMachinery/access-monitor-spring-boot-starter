package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接管理器（Connection Manager），用于控制系统层面的并发连接数，防止连接资源耗尽。
 * <p>
 * <strong>核心工作原理：</strong>
 * 在请求进入系统时（{@link #register(String, String)}），为该连接分配一个唯一ID并进行登记，
 * 同时维护两个维度的计数：
 * <ol>
 *   <li><strong>单IP连接数：</strong> 每个IP地址的并发连接数不得超过 {@code maxConnectionsPerIp}。</li>
 *   <li><strong>全局连接数：</strong> 系统总并发连接数不得超过 {@code globalMaxConnections}。</li>
 * </ol>
 * 当任一维度达到上限时，新连接将被拒绝（返回 HTTP 503）。
 * 在请求处理完成时（{@link #unregister(String, String)}），注销该连接，释放配额。
 * </p>
 * <p>
 * <strong>空闲连接清理：</strong>
 * 启动后台定时任务，每1分钟扫描一次所有活动连接，
 * 移除那些超过 {@code idleTimeout}（默认5分钟）未更新活动状态的连接。
 * 这种机制可以自动清理因网络异常、客户端崩溃等原因留下的僵尸连接，
 * 避免它们长期占用连接配额。
 * </p>
 * <p>
 * <strong>与 {@link RequestQueueManager} 的区别：</strong>
 * {@code RequestQueueManager} 关注的是"同一Key正在<strong>处理中</strong>的请求数量"（业务层面的并发），
 * 它允许超出的请求排队等待；
 * {@code ConnectionManager} 关注的是"系统层面<strong>已建立</strong>的TCP/HTTP连接总数"（传输层面的并发），
 * 它不允许排队，直接拒绝超限的新连接。
 * 两者分别从业务和传输两个层面保护系统。
 * </p>
 */
@Component
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * 连接管理器的配置属性，包含单IP连接上限、全局连接上限、空闲超时时间等参数。
     */
    private final AccessMonitorProperties properties;

    /**
     * 当前所有已注册的活动连接ID集合。
     * 使用 {@link ConcurrentHashMap#newKeySet()} 创建线程安全的 Set，
     * 用于快速判断某个连接是否已被注册以及统计全局连接总数。
     */
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();

    /**
     * 每个IP地址对应的连接数计数器映射表。
     * Key为IP地址，Value为该IP当前的活动连接数（使用原子计数器 {@link AtomicCounter}）。
     */
    private final Map<String, AtomicCounter> ipConnections = new ConcurrentHashMap<>();

    /**
     * 每个连接ID对应的最后活动时间。
     * Key为连接ID，Value为该连接最后一次调用 {@link #updateActivity(String)} 的时间。
     * 用于空闲超时检测。
     */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    /**
     * 后台清理任务的执行器，用于定期扫描并移除长期空闲的连接。
     */
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * 构造方法，初始化连接管理器并启动后台空闲连接清理任务。
     *
     * @param properties 连接管理器的配置属性
     */
    public ConnectionManager(AccessMonitorProperties properties) {
        this.properties = properties;
        startCleanupTask();
    }

    /**
     * 注册一个新连接。
     * <p>
     * 执行流程：
     * <ol>
     *   <li><strong>功能开关检查：</strong> 如果连接限制功能被禁用，直接放行。</li>
     *   <li><strong>全局连接数检查：</strong> 比较当前全局连接数与 {@code globalMaxConnections}，
     *       如果已达到上限，记录警告日志并拒绝该连接。</li>
     *   <li><strong>单IP连接数检查：</strong> 获取该IP的原子计数器，比较当前计数与 {@code maxConnectionsPerIp}，
     *       如果已达到上限，记录警告日志并拒绝该连接。</li>
     *   <li><strong>登记连接：</strong> 将连接ID加入 {@code activeConnections}，
     *       将该IP的计数器加1，并记录当前时间为最后活动时间。</li>
     * </ol>
     * </p>
     *
     * @param connectionId 连接的唯一标识，通常由请求ID和IP拼接而成（如 {@code "uuid@192.168.1.1"}）
     * @param ip           客户端IP地址
     * @return {@code true} 表示连接登记成功，请求可以继续处理；
     *         {@code false} 表示连接数超限，请求应被拒绝
     */
    public boolean register(String connectionId, String ip) {
        // 步骤1：功能开关检查。
        if (!properties.getConnectionLimit().isEnabled()) {
            return true;
        }

        // 步骤2：全局连接数上限检查。
        // activeConnections.size() 返回当前已注册的活动连接总数。
        if (activeConnections.size() >= properties.getConnectionLimit().getGlobalMaxConnections()) {
            logger.warn("全局连接数已达上限: {}/{}，拒绝新连接",
                activeConnections.size(), properties.getConnectionLimit().getGlobalMaxConnections());
            return false;
        }

        // 步骤3：单IP连接数上限检查。
        // 使用 computeIfAbsent 获取或创建该IP的计数器。
        AtomicCounter counter = ipConnections.computeIfAbsent(ip, k -> new AtomicCounter());
        // 注意：此处先获取计数再判断，存在微小的竞态窗口（两个线程同时获取到49，都判断通过，结果变成51）。
        // 在实际场景中，这种程度的偏差通常可以接受。如需严格保证不超过上限，可将判断和自增放在同步块内。
        if (counter.get() >= properties.getConnectionLimit().getMaxConnectionsPerIp()) {
            logger.warn("单IP连接数已达上限 - IP: {}, 当前: {}/{}，拒绝新连接",
                ip, counter.get(), properties.getConnectionLimit().getMaxConnectionsPerIp());
            return false;
        }

        // 步骤4：登记连接。
        activeConnections.add(connectionId);
        counter.increment();
        lastActivity.put(connectionId, Instant.now());
        return true;
    }

    /**
     * 注销一个已完成的连接。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>从 {@code activeConnections} 中移除该连接ID。</li>
     *   <li>从 {@code lastActivity} 中移除该连接的最后活动时间记录。</li>
     *   <li>将该IP对应的原子计数器减1。</li>
     * </ol>
     * </p>
     *
     * @param connectionId 连接的唯一标识
     * @param ip           客户端IP地址
     */
    public void unregister(String connectionId, String ip) {
        // 功能开关检查。
        if (!properties.getConnectionLimit().isEnabled()) {
            return;
        }

        // 从活动连接集合中移除。
        activeConnections.remove(connectionId);
        // 移除该连接的活动时间记录。
        lastActivity.remove(connectionId);

        // 将该IP的连接计数减1。
        AtomicCounter counter = ipConnections.get(ip);
        if (counter != null) {
            counter.decrement();
        }
    }

    /**
     * 更新指定连接的最后活动时间。
     * <p>
     * 当一个连接上有新的请求到达时（在 keep-alive 连接复用场景中），
     * 调用此方法刷新该连接的活动时间，防止其被空闲清理任务误判为僵尸连接。
     * </p>
     *
     * @param connectionId 连接的唯一标识
     */
    public void updateActivity(String connectionId) {
        if (connectionId != null) {
            lastActivity.put(connectionId, Instant.now());
        }
    }

    /**
     * 获取当前全局的活动连接总数。
     *
     * @return 全局连接数
     */
    public int getGlobalActiveCount() {
        return activeConnections.size();
    }

    /**
     * 获取指定IP的当前活动连接数。
     *
     * @param ip 客户端IP地址
     * @return 该IP的连接数，如果该IP无连接则返回 0
     */
    public int getIpActiveCount(String ip) {
        AtomicCounter counter = ipConnections.get(ip);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 启动后台空闲连接清理任务。
     * <p>
     * 每1分钟执行一次扫描：
     * 遍历 {@code lastActivity} 中的所有条目，计算每个连接的最后活动时间与当前时间的差值。
     * 如果差值超过 {@code idleTimeout}，则认为该连接已变为僵尸连接：
     * 从 {@code lastActivity} 和 {@code activeConnections} 中移除该连接，释放全局连接配额。
     * </p>
     * <p>
     * <strong>注意：</strong> 清理动作仅从内部计数器中移除连接记录，不会直接关闭底层的 TCP 连接。
     * 如果底层连接仍然保持（如客户端未断开），后续该连接上的新请求将重新触发 {@link #register}，
     * 只要连接配额未耗尽，请求仍可正常处理。
     * </p>
     */
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                // 读取配置的空闲超时时间。
                Duration idleTimeout = properties.getConnectionLimit().getIdleTimeout();
                // 计算截止时间：当前时间减去超时时间，早于该时间的连接被视为空闲。
                Instant cutoff = Instant.now().minus(idleTimeout);

                // 遍历并移除所有超过空闲超时时间未活动的连接。
                lastActivity.entrySet().removeIf(entry -> {
                    if (entry.getValue().isBefore(cutoff)) {
                        // 同时从活动连接集合中移除，释放全局配额。
                        activeConnections.remove(entry.getKey());
                        return true; // 从 lastActivity 中移除
                    }
                    return false; // 保留
                });
            } catch (Exception e) {
                logger.error("连接管理器后台清理任务执行异常", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 原子计数器内部类，封装 {@link AtomicInteger} 提供线程安全的自增/自减操作。
     * <p>
     * 使用内部类而非直接使用 {@link AtomicInteger} 的原因：
     * 使代码语义更清晰，同时为后续可能的扩展（如记录峰值、时间窗口内的增量统计等）预留空间。
     * </p>
     */
    private static class AtomicCounter {
        private final AtomicInteger value = new AtomicInteger(0);

        /**
         * 将计数加1并返回新值。
         *
         * @return 自增后的计数
         */
        public int increment() {
            return value.incrementAndGet();
        }

        /**
         * 将计数减1并返回新值。
         *
         * @return 自减后的计数
         */
        public int decrement() {
            return value.decrementAndGet();
        }

        /**
         * 获取当前计数。
         *
         * @return 当前值
         */
        public int get() {
            return value.get();
        }
    }
}
