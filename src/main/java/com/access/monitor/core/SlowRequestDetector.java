package com.access.monitor.core;

import com.access.monitor.properties.AccessMonitorProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 慢请求检测器（Slow Request Detector），用于识别并处理响应时间异常或已挂死的请求。
 * <p>
 * <strong>核心工作原理：</strong>
 * 当一个请求进入系统时，{@link #track(String, String, String, Thread)} 方法会为该请求创建一个
 * {@link TrackedRequest} 跟踪记录并存入 {@code activeRequests} 映射表。
 * 同时，调度两个定时任务分别在未来 {@code threshold} 和 {@code hangThreshold} 两个时间点执行检查：
 * <ol>
 *   <li><strong>慢请求检查（Threshold Check）：</strong> 请求处理时间超过 {@code threshold}（默认10秒）
     *       仍未完成时，输出 WARN 级别日志，提示运维人员关注该请求的耗时情况。</li>
   *   <li><strong>挂死请求检查（Hang Check）：</strong> 请求处理时间超过 {@code hangThreshold}（默认60秒）
     *       仍未完成时，输出 ERROR 级别日志。如果 {@code autoInterrupt} 为 {@code true}，
     *       还会向处理线程发送 {@link Thread#interrupt()} 中断信号，强制终止挂死操作，释放服务器资源。</li>
 * </ol>
 * </p>
 * <p>
 * <strong>完成通知：</strong>
 * 当请求正常处理完成、异步超时、发生异常或异步请求完成时，
 * {@link #complete(String)} 方法会被调用，从 {@code activeRequests} 中移除该跟踪记录，
 * 避免已完成的请求继续被误判为慢请求。
 * </p>
 * <p>
 * <strong>防御场景：</strong>
 * <ul>
 *   <li><strong>Slowloris 攻击：</strong> 攻击者以极慢的速度发送请求头或请求体，长时间占用连接但不完成请求，
     *       消耗服务器的连接池和处理线程。慢请求检测器可以识别这种长时间无进展的连接并主动中断。</li>
   *   <li><strong>后端服务阻塞：</strong> 当后端数据库或第三方接口响应缓慢时，
     *       检测器可以帮助定位哪些请求正在长时间等待，便于性能分析和故障排查。</li>
   *   <li><strong>资源泄漏：</strong> 及时发现并中断那些因代码缺陷而永远无法结束的请求，防止线程池被逐渐耗尽。</li>
 * </ul>
 * </p>
 */
@Component
public class SlowRequestDetector {

    private static final Logger logger = LoggerFactory.getLogger(SlowRequestDetector.class);

    /**
     * 慢请求检测器的配置属性，包含阈值、挂死阈值、是否自动中断等参数。
     */
    private final AccessMonitorProperties properties;

    /**
     * 定时任务执行器，用于调度慢请求检查和挂死请求检查。
     * 线程池大小为2，一个线程用于调度 threshold 检查，另一个用于 hangThreshold 检查。
     */
    private final ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(2);

    /**
     * 当前正在被跟踪的所有请求映射表。
     * Key为请求的唯一标识（requestId），Value为该请求的跟踪信息 {@link TrackedRequest}。
     * 使用 {@link ConcurrentHashMap} 保证并发环境下的读写安全。
     */
    private final Map<String, TrackedRequest> activeRequests = new ConcurrentHashMap<>();

    /**
     * 构造方法，初始化慢请求检测器。
     *
     * @param properties 慢请求检测器的配置属性
     */
    public SlowRequestDetector(AccessMonitorProperties properties) {
        this.properties = properties;
    }

    /**
     * 开始跟踪一个新请求。
     * <p>
     * 执行流程：
     * <ol>
     *   <li><strong>功能开关检查：</strong> 如果慢请求检测功能被禁用，直接返回，不做任何跟踪。</li>
     *   <li><strong>创建跟踪记录：</strong> 构建 {@link TrackedRequest} 对象，记录请求ID、IP、URI、
     *       处理线程引用和开始时间。</li>
     *   <li><strong>存入映射表：</strong> 将跟踪记录放入 {@code activeRequests}。</li>
     *   <li><strong>调度慢请求检查：</strong> 在 {@code threshold} 时间后执行 {@link #checkSlow(TrackedRequest)}。</li>
     *   <li><strong>调度挂死检查：</strong> 在 {@code hangThreshold} 时间后执行 {@link #checkHang(TrackedRequest)}。</li>
     * </ol>
     * </p>
     *
     * @param requestId      请求的唯一标识，通常由 {@link java.util.UUID} 生成
     * @param ip             客户端IP地址
     * @param uri            请求的URI路径
     * @param handlerThread  处理该请求的线程引用，用于后续可能的中断操作。如果为 {@code null}，则无法执行中断。
     */
    public void track(String requestId, String ip, String uri, Thread handlerThread) {
        // 功能开关检查。
        if (!properties.getSlowRequest().isEnabled()) {
            return;
        }

        // 创建跟踪记录，Instant.now() 记录请求进入系统的精确时间点。
        TrackedRequest tracked = new TrackedRequest(requestId, ip, uri, handlerThread, Instant.now());
        activeRequests.put(requestId, tracked);

        // 调度 threshold 检查任务。
        Duration threshold = properties.getSlowRequest().getThreshold();
        monitorExecutor.schedule(() -> checkSlow(tracked), threshold.toMillis(), TimeUnit.MILLISECONDS);

        // 调度 hangThreshold 检查任务。
        Duration hangThreshold = properties.getSlowRequest().getHangThreshold();
        monitorExecutor.schedule(() -> checkHang(tracked), hangThreshold.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 标记一个请求已处理完成，停止对其的跟踪。
     * <p>
     * 该方法应在以下场景被调用：
     * <ul>
     *   <li>请求正常处理完成（Filter 的 doFilter 方法返回）。</li>
     *   <li>异步请求完成（{@link MonitoringAsyncListener#onComplete}）。</li>
     *   <li>异步请求超时（{@link MonitoringAsyncListener#onTimeout}）。</li>
     *   <li>异步请求发生异常（{@link MonitoringAsyncListener#onError}）。</li>
     * </ul>
     * </p>
     *
     * @param requestId 请求的唯一标识
     */
    public void complete(String requestId) {
        activeRequests.remove(requestId);
    }

    /**
     * 获取当前所有正在被跟踪的请求副本。
     * <p>
     * 返回的是 {@link ConcurrentHashMap} 的浅拷贝，对返回的 Map 进行修改不会影响内部状态。
     * 主要用于监控端点展示当前正在执行中的慢请求列表。
     * </p>
     *
     * @return 当前活跃请求的映射表副本
     */
    public Map<String, TrackedRequest> getActiveRequests() {
        return new ConcurrentHashMap<>(activeRequests);
    }

    /**
     * 执行慢请求检查。
     * <p>
     * 在请求处理时间超过 {@code threshold} 时由定时任务调用。
     * 首先检查该请求是否仍在 {@code activeRequests} 中（即尚未完成），
     * 如果仍在执行中，计算已耗时时长并输出 WARN 级别日志。
     * </p>
     *
     * @param request 被跟踪的请求信息
     */
    private void checkSlow(TrackedRequest request) {
        // 如果请求已经完成（已从 activeRequests 中移除），不做任何处理。
        if (!activeRequests.containsKey(request.id)) {
            return;
        }
        // 计算从请求开始到当前时刻的耗时时长（毫秒）。
        long elapsed = Duration.between(request.startTime, Instant.now()).toMillis();
        // 输出警告日志，提示该请求已超出慢请求阈值。
        logger.warn("检测到慢请求 - ID: {}, IP: {}, URI: {}, 已耗时: {}ms",
            request.id, request.ip, request.uri, elapsed);
    }

    /**
     * 执行挂死请求检查。
     * <p>
     * 在请求处理时间超过 {@code hangThreshold} 时由定时任务调用。
     * 首先检查该请求是否仍在 {@code activeRequests} 中，
     * 如果仍在执行中，计算已耗时时长并输出 ERROR 级别日志。
     * 如果配置启用了自动中断（{@code autoInterrupt == true}）且持有线程引用，
     * 则向该线程发送中断信号。
     * </p>
     * <p>
     * <strong>关于线程中断的说明：</strong>
     * {@link Thread#interrupt()} 只是向目标线程发送一个中断信号（设置中断标志位），
     * 并不会强制终止线程的执行。目标线程需要在代码中主动检查中断状态（如通过 {@code Thread.interrupted()}）
     * 或在阻塞操作（如 {@code Thread.sleep()}、IO操作）中正确处理 {@link InterruptedException}，
     * 才能真正响应中断并优雅退出。如果目标线程完全忽略中断信号，即使调用了 {@code interrupt()}，
     * 该线程仍可能继续运行。因此，{@code autoInterrupt} 是一种<strong>协作式</strong>的中断机制，
     * 而非强制终止。
     * </p>
     *
     * @param request 被跟踪的请求信息
     */
    private void checkHang(TrackedRequest request) {
        // 如果请求已经完成，不做处理。
        if (!activeRequests.containsKey(request.id)) {
            return;
        }
        // 计算已耗时时长。
        long elapsed = Duration.between(request.startTime, Instant.now()).toMillis();
        // 输出错误日志，标记该请求为挂死/僵尸请求。
        logger.error("检测到挂死请求 - ID: {}, IP: {}, URI: {}, 已耗时: {}ms",
            request.id, request.ip, request.uri, elapsed);

        // 如果启用了自动中断且持有线程引用，发送中断信号。
        if (properties.getSlowRequest().isAutoInterrupt() && request.handlerThread != null) {
            logger.warn("正在中断挂死请求的处理线程: {}", request.handlerThread.getName());
            request.handlerThread.interrupt();
        }
    }

    /**
     * 被跟踪请求的数据载体类，用于存储单个请求的跟踪信息。
     * <p>
     * 所有字段均为 {@code public final}，创建后不可修改，保证数据的一致性和线程安全。
     * </p>
     */
    public static class TrackedRequest {
        /** 请求的唯一标识。 */
        public final String id;
        /** 客户端IP地址。 */
        public final String ip;
        /** 请求的URI路径。 */
        public final String uri;
        /** 处理该请求的线程引用，用于后续可能的中断操作。 */
        public final Thread handlerThread;
        /** 请求开始被跟踪的时间点。 */
        public final Instant startTime;

        public TrackedRequest(String id, String ip, String uri, Thread handlerThread, Instant startTime) {
            this.id = id;
            this.ip = ip;
            this.uri = uri;
            this.handlerThread = handlerThread;
            this.startTime = startTime;
        }
    }

    /**
     * 异步请求监听器，用于在异步Servlet请求完成时通知慢请求检测器停止跟踪。
     * <p>
     * 在 Spring Boot / Servlet 3.0+ 的异步请求模型中，请求可能在另一个线程中完成。
     * 此监听器实现了 {@link AsyncListener} 接口，监听异步请求的完成、超时和错误事件，
     * 确保无论异步请求以何种方式结束，都能正确调用 {@link SlowRequestDetector#complete(String)}
     * 释放跟踪记录。
     * </p>
     */
    public static class MonitoringAsyncListener implements AsyncListener {
        private final SlowRequestDetector detector;
        private final String requestId;

        public MonitoringAsyncListener(SlowRequestDetector detector, String requestId) {
            this.detector = detector;
            this.requestId = requestId;
        }

        /**
         * 异步请求正常完成时调用。
         */
        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            detector.complete(requestId);
        }

        /**
         * 异步请求超时时调用。
         */
        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            detector.complete(requestId);
        }

        /**
         * 异步请求发生错误时调用。
         */
        @Override
        public void onError(AsyncEvent event) throws IOException {
            detector.complete(requestId);
        }

        /**
         * 异步请求重新启动时调用（Servlet 规范要求实现，通常为空实现）。
         */
        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            // 无需处理
        }
    }
}
