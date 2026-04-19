package com.access.monitor.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 访问记录实体类，用于存储单个Key（IP或账户）的访问统计信息。
 * <p>
 * 每个被监控的客户端（按IP或账户标识）都会对应一个 {@code AccessRecord} 实例，
 * 该实例记录了该Key在固定时间窗口内的请求次数、封禁状态、并发请求数等信息。
 * </p>
 * <p>
 * <strong>线程安全说明：</strong>
 * {@code requestCount} 字段使用 {@link AtomicInteger} 保证原子性操作；
 * 其余字段（如 {@code bannedUntil}、{@code firstRequestTime}）使用 {@code volatile} 修饰，
 * 确保多线程环境下的可见性。但在进行"检查+修改"的复合操作时（如判断是否在封禁期内并决定是否放行），
 * 调用方（如 {@link RateLimiter}）需要在外部进行同步控制，以避免竞态条件。
 * </p>
 */
public class AccessRecord {

    /**
     * 该访问记录对应的唯一标识Key，格式通常为 {@code "ip:192.168.1.1"} 或 {@code "acct:zhangsan"}。
     * 使用 {@code final} 修饰，创建后不可变更。
     */
    private final String key;

    /**
     * 当前时间窗口内已累计的请求次数。
     * 使用 {@link AtomicInteger} 保证自增操作的原子性，避免并发请求导致计数丢失。
     */
    private final AtomicInteger requestCount;

    /**
     * 当前统计时间窗口的起始时间。
     * 当窗口过期后（超过1分钟），会调用 {@link #reset()} 方法重置为当前时间。
     * 使用 {@code volatile} 确保多线程读取时的可见性。
     */
    private volatile Instant firstRequestTime;

    /**
     * 封禁截止时间。如果为 {@code null}，表示该Key当前未被封禁。
     * 当请求超过速率限制时，{@link RateLimiter} 会调用 {@link #ban(Duration)} 设置此值。
     * 使用 {@code volatile} 确保封禁状态变更对所有线程立即可见。
     */
    private volatile Instant bannedUntil;

    /**
     * 当前正在并发处理的请求数量。
     * 该字段主要用于统计和监控，实际并发控制由 {@link RequestQueueManager} 通过 {@link java.util.concurrent.Semaphore} 实现。
     */
    private volatile int concurrentRequests;

    /**
     * 该Key最近一次发起请求的时间，用于判断连接是否空闲以及辅助清理逻辑。
     */
    private volatile Instant lastAccessTime;

    /**
     * 构造方法，初始化一条新的访问记录。
     *
     * @param key 该记录的唯一标识，如IP地址或账户名
     */
    public AccessRecord(String key) {
        this.key = key;
        this.requestCount = new AtomicInteger(0);
        this.firstRequestTime = Instant.now();
        this.lastAccessTime = Instant.now();
    }

    /**
     * 原子地将当前窗口的请求计数加1，并返回加1后的最新值。
     * <p>
     * 该方法线程安全，可在高并发场景下被多线程同时调用而不会丢失计数。
     * </p>
     *
     * @return 自增后的请求次数
     */
    public int incrementAndGet() {
        return requestCount.incrementAndGet();
    }

    /**
     * 获取当前时间窗口内的请求总次数。
     *
     * @return 请求计数
     */
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * 重置该访问记录的所有统计信息。
     * <p>
     * 通常在时间窗口过期后由 {@link RateLimiter} 调用，将计数清零并重新设定窗口起始时间，
     * 同时解除封禁状态（将 {@code bannedUntil} 置为 {@code null}）。
     * </p>
     * <p>
     * <strong>注意：</strong> 调用此方法前，调用方必须已持有该记录对象的同步锁，
     * 避免重置操作与其他读写操作发生竞态。
     * </p>
     */
    public void reset() {
        requestCount.set(0);
        firstRequestTime = Instant.now();
        bannedUntil = null;
    }

    /**
     * 对该Key执行封禁操作。
     * <p>
     * 设置 {@code bannedUntil} 为当前时间加上封禁时长，在此时间点之前，
     * {@link #isBanned()} 将返回 {@code true}，所有请求都会被拒绝。
     * </p>
     *
     * @param duration 封禁时长，例如 {@code Duration.ofMinutes(10)}
     */
    public void ban(Duration duration) {
        this.bannedUntil = Instant.now().plus(duration);
    }

    /**
     * 检查该Key当前是否处于封禁状态。
     * <p>
     * 逻辑如下：
     * <ol>
     *   <li>如果 {@code bannedUntil} 为 {@code null}，说明从未被封禁，返回 {@code false}。</li>
     *   <li>如果当前时间已超过 {@code bannedUntil}，说明封禁已过期，自动清除封禁状态并返回 {@code false}。</li>
     *   <li>否则，返回 {@code true}，表示仍在封禁期内。</li>
     * </ol>
     * </p>
     *
     * @return {@code true} 表示当前处于封禁状态，{@code false} 表示未被封禁或封禁已过期
     */
    public boolean isBanned() {
        if (bannedUntil == null) {
            return false;
        }
        if (Instant.now().isAfter(bannedUntil)) {
            bannedUntil = null;
            return false;
        }
        return true;
    }

    /**
     * 获取封禁截止时间点。
     *
     * @return 封禁截止时间，如果当前未被禁则返回 {@code null}
     */
    public Instant getBannedUntil() {
        return bannedUntil;
    }

    /**
     * 获取当前统计窗口的起始时间。
     *
     * @return 窗口起始时间
     */
    public Instant getFirstRequestTime() {
        return firstRequestTime;
    }

    /**
     * 判断当前统计窗口是否已经过期。
     * <p>
     * 以 {@code firstRequestTime} 为起点，加上给定的 {@code window} 时长，
     * 如果当前时间已超过该计算值，则窗口过期，需要重置计数。
     * </p>
     *
     * @param window 时间窗口长度，通常为 1 分钟
     * @return {@code true} 表示窗口已过期，{@code false} 表示仍在窗口期内
     */
    public boolean isWindowExpired(Duration window) {
        return Instant.now().isAfter(firstRequestTime.plus(window));
    }

    /**
     * 获取该记录对应的Key。
     *
     * @return Key字符串
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取当前正在并发处理的请求数量。
     *
     * @return 并发请求数
     */
    public int getConcurrentRequests() {
        return concurrentRequests;
    }

    /**
     * 并发请求计数加1。
     * <p>
     * 通常在请求开始处理时由 {@link AccessMonitorFilter} 调用（如需要此统计）。
     * </p>
     */
    public void incrementConcurrent() {
        concurrentRequests++;
    }

    /**
     * 并发请求计数减1，确保不会减到负数。
     * <p>
     * 通常在请求处理完成时调用。
     * </p>
     */
    public void decrementConcurrent() {
        concurrentRequests = Math.max(0, concurrentRequests - 1);
    }

    /**
     * 获取最近一次访问的时间。
     *
     * @return 最后访问时间
     */
    public Instant getLastAccessTime() {
        return lastAccessTime;
    }

    /**
     * 更新最近一次访问时间为当前时间。
     * <p>
     * 每次请求到达时调用，用于空闲超时检测和活跃度统计。
     * </p>
     */
    public void updateLastAccessTime() {
        this.lastAccessTime = Instant.now();
    }
}
