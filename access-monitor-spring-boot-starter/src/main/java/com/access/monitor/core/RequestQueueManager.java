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
 * 请求队列管理器，用于对超出并发上限的请求进行排队等待管理。
 * <p>
 * <strong>核心工作原理：</strong>
 * 对每个Key（IP或账户）维护一个独立的 {@link Semaphore}（信号量）和一个 {@link BlockingQueue}（阻塞队列）。
 * 信号量控制<strong>同时处理</strong>的请求数量上限（{@code maxConcurrentPerKey}），
 * 阻塞队列存放等待执行的请求（上限为 {@code maxQueueSizePerKey}）。
 * 当并发数未满时，新请求直接获取信号量并立即执行；
 * 当并发数已满但队列未满时，新请求进入队列排队；
 * 当队列已满时，新请求被直接拒绝，返回 HTTP 503。
 * </p>
 * <p>
 * <strong>队列唤醒机制：</strong>
 * 当一个请求处理完成时，调用 {@link #release(String)} 方法，
 * 该方法优先从队列中取出下一个等待的请求（通过 {@link QueuedRequest#grant()} 唤醒），
 * 如果队列中没有等待的请求，则释放信号量许可供后续新请求使用。
 * 这种设计确保等待中的请求优先获得执行机会，而非新到达的请求抢占资源（FIFO公平性）。
 * </p>
 * <p>
 * <strong>线程安全：</strong>
 * {@code keySemaphores} 和 {@code keyQueues} 均使用 {@link ConcurrentHashMap} 存储，
 * 保证并发环境下对映射表的读写安全。
 * 但需要注意：{@code Semaphore} 和 {@code BlockingQueue} 本身已经是线程安全的并发容器，无需额外加锁。
 * </p>
 */
@Component
public class RequestQueueManager {

    private static final Logger logger = LoggerFactory.getLogger(RequestQueueManager.class);

    /**
     * 队列管理器的配置属性，包含最大并发数、最大队列长度、排队超时时间等参数。
     */
    private final AccessMonitorProperties properties;

    /**
     * 存储每个Key对应的信号量（Semaphore）。
     * 信号量的初始许可数等于 {@code maxConcurrentPerKey}，用于控制同一Key的并发请求数。
     * 使用 {@code ConcurrentHashMap} + {@code computeIfAbsent} 按需懒加载创建信号量。
     */
    private final Map<String, Semaphore> keySemaphores = new ConcurrentHashMap<>();

    /**
     * 存储每个Key对应的阻塞队列（BlockingQueue）。
     * 队列中存放的是 {@link QueuedRequest} 对象，代表正在等待执行的请求。
     * 队列容量上限为 {@code maxQueueSizePerKey}。
     */
    private final Map<String, BlockingQueue<QueuedRequest>> keyQueues = new ConcurrentHashMap<>();

    /**
     * 后台调度执行器，用于定期清理长期未使用的Key对应的信号量和队列，防止内存泄漏。
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 构造方法，初始化队列管理器并启动后台清理任务。
     *
     * @param properties 队列管理器的配置属性
     */
    public RequestQueueManager(AccessMonitorProperties properties) {
        this.properties = properties;
        startCleanupTask();
    }

    /**
     * 尝试为指定Key获取执行许可。
     * <p>
     * 执行流程：
     * <ol>
     *   <li><strong>功能开关检查：</strong> 如果队列功能被禁用，直接放行。</li>
     *   <li><strong>获取信号量：</strong> 通过 {@code computeIfAbsent} 获取或创建该Key的信号量。</li>
     *   <li><strong>获取队列：</strong> 通过 {@code computeIfAbsent} 获取或创建该Key的阻塞队列。</li>
     *   <li><strong>直接获取许可：</strong> 尝试通过 {@link Semaphore#tryAcquire()} 无阻塞地获取许可。
     *       如果成功，说明当前并发数未满，请求直接放行执行。</li>
     *   <li><strong>队列满检查：</strong> 如果并发已满，检查队列剩余容量。
     *       如果 {@code remainingCapacity() == 0}，说明队列已满，直接拒绝请求。</li>
     *   <li><strong>入队等待：</strong> 队列未满，创建 {@link QueuedRequest} 并尝试入队。
     *       如果入队失败（极端并发场景下的竞态），直接拒绝。</li>
     *   <li><strong>阻塞等待：</strong> 调用 {@link QueuedRequest#await(Duration)} 阻塞等待，
     *       直到被 {@link #release(String)} 唤醒，或等待超时（{@code queueTimeout}）。</li>
     *   <li><strong>超时清理：</strong> 如果等待超时仍未被唤醒，从队列中移除该等待请求，返回拒绝。</li>
     * </ol>
     * </p>
     *
     * @param key 队列标识，格式如 {@code "queue:ip:192.168.1.1"} 或 {@code "queue:acct:zhangsan"}
     * @return {@code true} 表示成功获取许可（直接或通过排队），请求可以执行；
     *         {@code false} 表示队列已满或等待超时，请求被拒绝
     * @throws InterruptedException 如果等待过程中当前线程被中断
     */
    public boolean tryAcquire(String key) throws InterruptedException {
        // 步骤1：功能开关检查。如果队列功能在配置中被关闭，直接放行。
        if (!properties.getQueue().isEnabled()) {
            return true;
        }

        // 步骤2：获取或创建该Key的信号量。使用 computeIfAbsent 保证线程安全且按需创建。
        Semaphore semaphore = keySemaphores.computeIfAbsent(key,
            k -> new Semaphore(properties.getQueue().getMaxConcurrentPerKey()));

        // 步骤3：获取或创建该Key的阻塞队列。使用 LinkedBlockingQueue 实现 FIFO 排队。
        BlockingQueue<QueuedRequest> queue = keyQueues.computeIfAbsent(key,
            k -> new LinkedBlockingQueue<>(properties.getQueue().getMaxQueueSizePerKey()));

        // 步骤4：尝试直接获取信号量许可。tryAcquire() 是非阻塞的，立即返回结果。
        // 如果获取成功，说明当前并发数未达到上限，请求可以直接执行。
        if (semaphore.tryAcquire()) {
            return true;
        }

        // 步骤5：并发数已满，检查队列是否还有空位。
        // remainingCapacity() 返回队列剩余的可用容量，为0表示队列已满。
        if (queue.remainingCapacity() == 0) {
            logger.warn("队列已满，拒绝请求 - Key: {}", key);
            return false;
        }

        // 步骤6：创建等待请求对象并尝试加入队列。
        QueuedRequest request = new QueuedRequest();
        if (!queue.offer(request)) {
            // 入队失败（理论上极少发生，因为前面已检查 remainingCapacity）
            return false;
        }

        // 步骤7：阻塞等待被唤醒或超时。
        try {
            boolean acquired = request.await(properties.getQueue().getQueueTimeout());
            // 步骤8：如果等待超时，从队列中移除该请求，防止队列中存在已失效的等待项。
            if (!acquired) {
                queue.remove(request);
            }
            return acquired;
        } catch (InterruptedException e) {
            // 等待过程中线程被中断，从队列中移除并抛出异常。
            queue.remove(request);
            throw e;
        }
    }

    /**
     * 释放指定Key的执行许可。
     * <p>
     * 当一个请求处理完成时，必须调用此方法归还资源。执行流程：
     * <ol>
     *   <li><strong>功能开关检查：</strong> 如果队列功能被禁用，直接返回。</li>
     *   <li><strong>获取信号量和队列：</strong> 从映射表中查找对应Key的信号量和队列。</li>
     *   <li><strong>优先唤醒等待请求：</strong> 从队列中 poll() 出第一个等待的 {@link QueuedRequest}，
     *       如果存在，调用其 {@code grant()} 方法唤醒它执行。此时不释放信号量，
     *       因为被唤醒的请求会占用刚释放出来的执行槽位。</li>
     *   <li><strong>释放信号量：</strong> 如果队列中没有等待的请求，调用 {@link Semaphore#release()}
     *       将许可归还，供后续新到达的请求使用。</li>
     * </ol>
     * </p>
     *
     * @param key 队列标识，应与 {@link #tryAcquire(String)} 传入的Key一致
     */
    public void release(String key) {
        // 功能开关检查。
        if (!properties.getQueue().isEnabled()) {
            return;
        }

        // 获取该Key对应的信号量。如果信号量不存在（如已被清理），直接返回。
        Semaphore semaphore = keySemaphores.get(key);
        if (semaphore == null) {
            return;
        }

        // 获取该Key对应的队列。
        BlockingQueue<QueuedRequest> queue = keyQueues.get(key);
        if (queue != null) {
            // 优先从队列中取出等待的请求并唤醒它。
            // 被唤醒的请求将直接获得执行权，无需再次竞争信号量。
            QueuedRequest next = queue.poll();
            if (next != null) {
                next.grant();
                return;
            }
        }

        // 队列中没有等待的请求，释放信号量许可。
        semaphore.release();
    }

    /**
     * 查询指定Key当前队列中的等待请求数量。
     *
     * @param key 队列标识
     * @return 当前排队中的请求数量，如果该Key无队列则返回 0
     */
    public int getQueueSize(String key) {
        BlockingQueue<?> queue = keyQueues.get(key);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 查询指定Key当前正在并发执行的请求数量。
     * <p>
     * 计算方法：{@code maxConcurrentPerKey} 减去信号量的可用许可数。
     * 例如，最大并发为10，信号量还剩3个许可，说明当前有 7 个请求正在执行。
     * </p>
     *
     * @param key 队列标识
     * @return 当前正在执行的请求数量
     */
    public int getActiveCount(String key) {
        Semaphore semaphore = keySemaphores.get(key);
        if (semaphore == null) {
            return 0;
        }
        return properties.getQueue().getMaxConcurrentPerKey() - semaphore.availablePermits();
    }

    /**
     * 启动后台清理任务。
     * <p>
     * 每5分钟执行一次清理：
     * 移除那些队列已为空（无等待请求）的Key对应的队列条目。
     * 信号量的清理在此版本中较为保守，暂不主动移除，以避免并发场景下的竞态问题。
     * </p>
     */
    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 清理空队列：如果某个Key的队列中已无任何等待请求，则从映射表中移除该队列。
                keyQueues.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                // 信号量清理：此处暂时保留所有信号量，因为并发场景下难以安全判断信号量是否已完全空闲。
                keySemaphores.entrySet().removeIf(entry -> {
                    return false;
                });
            } catch (Exception e) {
                logger.error("队列管理器后台清理任务执行异常", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 内部类，代表一个正在队列中等待执行的请求。
     * <p>
     * 使用 {@link CountDownLatch} 实现阻塞等待：
     * 当请求进入队列后，调用 {@link #await(Duration)} 阻塞当前线程；
     * 当 {@link RequestQueueManager#release(String)} 从队列中取出该请求时，
     * 调用 {@link #grant()} 将 {@code granted} 置为 {@code true} 并唤醒等待线程。
     * </p>
     * <p>
     * <strong>为什么使用 CountDownLatch 而非 Object.wait/notify？</strong>
     * CountDownLatch 是 Java 并发包提供的高级同步工具，相比 wait/notify 更加安全易用：
     * 不需要在 synchronized 块内调用，且不会受虚假唤醒（spurious wakeup）影响。
     * </p>
     */
    private static class QueuedRequest {

        /**
         * 计数门闩，初始值为1。调用 {@code countDown()} 后变为0，所有在 {@code await()} 上等待的线程被唤醒。
         */
        private final CountDownLatch latch = new CountDownLatch(1);

        /**
         * 标记该请求是否真正被授予了执行权。
         * 使用 {@code volatile} 确保 {@code grant()} 方法修改后，等待线程立即可见。
         * 如果等待是因超时而非被唤醒，此值为 {@code false}。
         */
        private volatile boolean granted = false;

        /**
         * 阻塞等待被唤醒或超时。
         *
         * @param timeout 最大等待时长
         * @return {@code true} 表示被成功唤醒并授予执行权；{@code false} 表示等待超时
         * @throws InterruptedException 如果等待过程中当前线程被中断
         */
        public boolean await(Duration timeout) throws InterruptedException {
            // 在 latch 上等待指定时间。latch 变为0时返回 true，超时返回 false。
            boolean acquired = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // 必须同时满足：latch 被唤醒（acquired == true）且 granted == true。
            // 因为 latch 的 countDown 可能由其他逻辑触发（如系统关闭），需要 granted 标志确认是真正的授权。
            return acquired && granted;
        }

        /**
         * 授予该请求执行权并唤醒等待线程。
         * <p>
         * 由 {@link RequestQueueManager#release(String)} 在请求处理完成时调用。
         * 先将 {@code granted} 置为 true，再 countDown 唤醒等待线程，确保唤醒后 granted 已经可见。
         * </p>
         */
        public void grant() {
            granted = true;
            latch.countDown();
        }
    }
}
