package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流量整形器（Traffic Shaper），采用令牌桶（Token Bucket）算法对全系统的请求速率进行平滑控制。
 * <p>
 * <strong>核心工作原理：</strong>
 * 维护一个全局的信号量 {@link Semaphore} 作为令牌桶，初始许可数等于 {@code burstCapacity}（桶容量）。
 * 每个非优先请求进入系统时需要从桶中获取一个令牌（{@link Semaphore#tryAcquire()}）。
 * 如果桶中有令牌，请求立即通过；如果桶为空，请求被拒绝或阻塞等待。
 * </p>
 * <p>
 * <strong>令牌补充机制：</strong>
 * 启动一个独立的守护线程（{@code traffic-shaper-refill}），每秒执行一次补充逻辑：
 * 计算当前桶中缺少的令牌数（{@code burstCapacity - availablePermits}），
 * 然后补充 {@code Math.min(globalRatePerSecond, 缺少数量)} 个令牌。
 * 这样既保证了平均速率不超过 {@code globalRatePerSecond}，又允许在不超过 {@code burstCapacity} 的前提下处理突发流量。
 * </p>
 * <p>
 * <strong>优先路径：</strong>
 * 某些路径（如健康检查 {@code /health}）被配置为优先路径，这些请求会绕过令牌桶直接放行，
 * 确保在高负载场景下监控系统仍能获取服务健康状态。
 * </p>
 * <p>
 * <strong>与 {@link RateLimiter} 的区别：</strong>
 * {@code RateLimiter} 是按Key（IP/账户）维度进行限流，用于防御单客户端的暴力请求；
 * {@code TrafficShaper} 是按全局维度进行速率控制，用于平滑全系统的总流量，防止所有客户端的聚合请求压垮服务。
 * 两者配合使用，形成"单客户端限流 + 全局流量整形"的两层防御体系。
 * </p>
 */
@Component
public class TrafficShaper {

    private static final Logger logger = LoggerFactory.getLogger(TrafficShaper.class);

    /**
     * 流量整形器的配置属性，包含全局速率、桶容量、优先路径等参数。
     */
    private final AccessMonitorProperties properties;

    /**
     * 全局信号量，代表令牌桶中的可用令牌数量。
     * 初始许可数为 {@code burstCapacity}，每次请求获取一个许可，补充线程每秒按速率回充。
     */
    private final Semaphore globalSemaphore;

    /**
     * 当前全局正在处理的请求数量计数器。
     * 主要用于监控统计，实际流量控制由 {@code globalSemaphore} 完成。
     */
    private final AtomicInteger currentGlobal = new AtomicInteger(0);

    /**
     * 构造方法，初始化令牌桶并启动令牌补充守护线程。
     *
     * @param properties 流量整形器的配置属性
     */
    public TrafficShaper(AccessMonitorProperties properties) {
        this.properties = properties;
        // 初始时桶是满的，拥有 burstCapacity 个令牌。
        this.globalSemaphore = new Semaphore(properties.getTrafficShaper().getBurstCapacity());
        startRefillTask();
    }

    /**
     * 无阻塞地尝试从令牌桶中获取一个令牌。
     * <p>
     * 如果桶中有可用令牌，立即获取并返回 {@code true}；
     * 如果桶已空，立即返回 {@code false}，调用方应拒绝该请求或采取降级措施。
     * </p>
     *
     * @return {@code true} 表示成功获取令牌，请求可以执行；{@code false} 表示桶已空
     */
    public boolean tryEnter() {
        // 功能开关检查。如果流量整形被禁用，直接放行。
        if (!properties.getTrafficShaper().isEnabled()) {
            return true;
        }
        // 尝试无阻塞获取一个许可（令牌）。
        return globalSemaphore.tryAcquire();
    }

    /**
     * 带超时地尝试从令牌桶中获取一个令牌。
     * <p>
     * 如果桶中有可用令牌，立即获取并返回 {@code true}；
     * 如果桶已空，当前线程阻塞等待，直到有令牌可用或超过指定的超时时间。
     * </p>
     *
     * @param timeoutMs 最大等待时间，单位毫秒
     * @return {@code true} 表示成功获取令牌；{@code false} 表示等待超时
     * @throws InterruptedException 如果等待过程中当前线程被中断
     */
    public boolean tryEnter(long timeoutMs) throws InterruptedException {
        // 功能开关检查。
        if (!properties.getTrafficShaper().isEnabled()) {
            return true;
        }
        // 带超时的阻塞获取。
        return globalSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 向令牌桶归还一个令牌。
     * <p>
     * 当一个请求处理完成时，必须调用此方法归还令牌，否则桶中的令牌会逐渐耗尽，导致后续所有请求被拒绝。
     * 注意：{@link Semaphore#release()} 允许释放超过初始容量的许可数，因此即使归还次数多于获取次数，
     * 也不会导致异常，但可能会造成桶容量临时超过 {@code burstCapacity}，这在下一轮的补充逻辑中会被自然修正。
     * </p>
     */
    public void leave() {
        // 功能开关检查。
        if (!properties.getTrafficShaper().isEnabled()) {
            return;
        }
        // 释放一个许可回桶。
        globalSemaphore.release();
        // 更新全局并发计数（递减）。
        currentGlobal.decrementAndGet();
    }

    /**
     * 判断给定路径是否为优先路径。
     * <p>
     * 优先路径列表中的路径采用<strong>前缀匹配</strong>：
     * 例如配置 {@code /health} 可以匹配 {@code /health}、{@code /health/live}、{@code /health/ready} 等。
     * 优先路径的请求会绕过令牌桶检查，直接放行。
     * </p>
     *
     * @param path 请求的URI路径
     * @return {@code true} 表示该路径是优先路径，应直接放行
     */
    public boolean isPrioritizedPath(String path) {
        // 空路径不做优先处理。
        if (path == null) {
            return false;
        }
        // 遍历配置的优先路径列表，使用 startsWith 进行前缀匹配。
        return properties.getTrafficShaper().getPrioritizedPaths().stream()
            .anyMatch(path::startsWith);
    }

    /**
     * 获取当前令牌桶中的可用令牌数量。
     * <p>
     * 主要用于监控端点展示系统当前的流量余量，帮助运维人员判断系统负载情况。
     * </p>
     *
     * @return 当前可用的令牌数
     */
    public int getAvailablePermits() {
        return globalSemaphore.availablePermits();
    }

    /**
     * 启动令牌补充守护线程。
     * <p>
     * 该线程以守护线程（Daemon Thread）方式运行，意味着它不会阻止 JVM 正常退出。
     * 线程名称为 {@code traffic-shaper-refill}，便于在日志和线程Dump中识别。
     * </p>
     * <p>
     * 补充逻辑（每秒执行一次）：
     * <ol>
     *   <li>获取当前桶中可用的许可数（{@code availablePermits}）。</li>
     *   <li>计算需要补充的数量：{@code Math.min(globalRatePerSecond, burstCapacity - current)}。
     *       即：每秒最多补充 {@code globalRatePerSecond} 个，同时不能超过桶的容量上限。</li>
     *   <li>如果 {@code toAdd > 0}，调用 {@link Semaphore#release(int)} 将令牌补充回桶中。</li>
     * </ol>
     * </p>
     */
    private void startRefillTask() {
        Thread refillThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    // 每秒执行一次补充。
                    Thread.sleep(1000);

                    // 读取当前配置值（支持动态配置变更，虽然 Spring Boot 配置通常不会热更新，
                    // 但通过引用 properties 对象，理论上可以配合配置中心实现动态调整）。
                    int rate = properties.getTrafficShaper().getGlobalRatePerSecond();
                    int burst = properties.getTrafficShaper().getBurstCapacity();

                    // 获取当前桶中剩余的可用许可数。
                    int current = globalSemaphore.availablePermits();

                    // 计算本次应补充的令牌数：取 "每秒速率" 和 "桶剩余空间" 的较小值。
                    int toAdd = Math.min(rate, burst - current);

                    if (toAdd > 0) {
                        // 将计算出的令牌数释放回信号量，相当于向桶中添加令牌。
                        globalSemaphore.release(toAdd);
                    }
                } catch (InterruptedException e) {
                    // 收到中断信号时，设置中断标志并退出循环。
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        // 设置为守护线程，避免阻止 JVM 退出。
        refillThread.setDaemon(true);
        // 设置线程名称，便于调试和监控。
        refillThread.setName("traffic-shaper-refill");
        refillThread.start();
    }
}
