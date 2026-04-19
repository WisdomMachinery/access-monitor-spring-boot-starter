package com.access.monitor.filter;

import com.access.monitor.core.*;
import com.access.monitor.properties.AccessMonitorProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.UUID;

/**
 * 访问监控过滤器（Access Monitor Filter），是整个访问监控模块的<strong>核心入口</strong>。
 * <p>
 * 该过滤器以 Servlet Filter 的形式嵌入 Spring Boot 的请求处理链路中，
 * 对每个进入系统的 HTTP 请求执行多层防御检查。
 * 通过 {@code @Order(Ordered.HIGHEST_PRECEDENCE + 10)} 注解确保它在过滤器链的<strong>最前端</strong>执行，
 * 从而在请求到达业务逻辑之前完成安全校验。
 * </p>
 * <p>
 * <strong>五层防御检查的执行顺序（按优先级从高到低）：</strong>
 * <ol>
 *   <li><strong>连接数限制（Connection Limit）：</strong> 检查单IP和全局的连接数是否已达上限。
 *       这是最外层的防线，防止连接资源被耗尽。失败返回 HTTP 503。</li>
 *   <li><strong>速率限制（Rate Limiting）：</strong> 检查该IP/账户在1分钟内的请求次数是否超过阈值。
 *       失败返回 HTTP 429，并携带 {@code Retry-After} 响应头提示客户端何时可以重试。</li>
 *   <li><strong>流量整形（Traffic Shaping）：</strong> 检查全局令牌桶中是否还有可用令牌。
 *       优先路径（如健康检查）跳过此检查。失败返回 HTTP 503。</li>
 *   <li><strong>队列管理（Queue Management）：</strong> 检查同一Key的并发数是否已满，未满直接放行，
 *       已满则尝试进入队列排队，队列已满则拒绝。失败返回 HTTP 503。</li>
 *   <li><strong>慢请求跟踪（Slow Request Tracking）：</strong> 不阻塞请求，仅为每个请求建立跟踪记录，
 *       用于后续检测慢请求和挂死请求。</li>
 * </ol>
 * </p>
 * <p>
 * <strong>请求标识与上下文传递：</strong>
 * 每个请求会生成一个唯一的 {@code requestId}（UUID格式），并将其存入 {@code HttpServletRequest} 的属性中，
 * 便于后续的业务代码、日志框架、监控系统中统一追踪该请求的全生命周期。
 * 同时，提取的客户端IP也会存入请求属性，方便下游组件直接使用而无需重复解析。
 * </p>
 * <p>
 * <strong>IP提取逻辑：</strong>
 * 考虑到生产环境通常部署在反向代理（Nginx、SLB、CDN）之后，直接使用 {@code request.getRemoteAddr()}
 * 获取到的是代理服务器的IP而非真实客户端IP。因此，本过滤器按照以下优先级从请求头中提取真实IP：
 * <ol>
 *   <li>{@code X-Forwarded-For} - 最常用的代理转发头，可能包含多个IP（逗号分隔），取第一个。</li>
 *   <li>{@code Proxy-Client-IP} - 部分代理软件使用的头。</li>
 *   <li>{@code WL-Proxy-Client-IP} - WebLogic 代理使用的头。</li>
 *   <li>{@code X-Real-IP} - Nginx 等反向代理常用的头。</li>
 *   <li>{@code request.getRemoteAddr()} - 无代理头时的回退方案。</li>
 * </ol>
 * </p>
 * <p>
 * <strong>账户提取逻辑：</strong>
 * 支持从多个来源获取用户账户标识，优先级如下：
 * <ol>
 *   <li>{@code X-User-Id} 请求头 - 适用于微服务架构中上游服务传递的用户ID。</li>
 *   <li>{@code request.getUserPrincipal().getName()} - 基于 Spring Security 等安全框架的已认证用户。</li>
 *   <li>{@code X-API-Key} 请求头 - 适用于 API Key 认证的客户端，格式化为 {@code "api:xxx"}。</li>
 * </ol>
 * 如果以上来源均无法获取账户信息，则回退到按IP限流。
 * </p>
 * <p>
 * <strong>资源释放的保障：</strong>
 * 使用 try-finally 结构确保无论请求处理成功、失败还是被中断，
 * 所有已获取的资源（队列许可、令牌桶令牌、连接登记）都会被正确释放。
 * 特别是在 {@code InterruptedException} 场景下，会重新设置线程中断标志并释放资源。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessMonitorFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessMonitorFilter.class);

    /** 访问监控模块的配置属性。 */
    private final AccessMonitorProperties properties;
    /** 速率限制器，用于按IP/账户维度限制请求频率。 */
    private final RateLimiter rateLimiter;
    /** 请求队列管理器，用于控制同一Key的并发数和排队。 */
    private final RequestQueueManager queueManager;
    /** 流量整形器，用于全局令牌桶速率控制。 */
    private final TrafficShaper trafficShaper;
    /** 慢请求检测器，用于跟踪和检测长时间未完成的请求。 */
    private final SlowRequestDetector slowRequestDetector;
    /** 连接管理器，用于控制系统层面的并发连接数。 */
    private final ConnectionManager connectionManager;

    /**
     * 构造方法，由 Spring 容器通过依赖注入自动组装所有监控组件。
     */
    public AccessMonitorFilter(AccessMonitorProperties properties,
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
     * 过滤器的核心方法，对每次 HTTP 请求执行完整的访问监控检查链。
     * <p>
     * 执行顺序严格按照"连接数 → 速率限制 → 流量整形 → 队列管理 → 慢请求跟踪"进行。
     * 任何一个环节检查失败，请求都会被立即拦截，不再进入后续环节和业务逻辑。
     * </p>
     *
     * @param request  ServletRequest 对象
     * @param response ServletResponse 对象
     * @param chain    FilterChain，用于将检查通过的请求传递给后续的 Filter 和 Servlet
     * @throws IOException      当写入响应体时发生 IO 异常
     * @throws ServletException 当 FilterChain 执行过程中发生 Servlet 异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // 全局开关检查。如果整个监控模块被禁用，直接放行，不做任何拦截。
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 将请求和响应转换为 HTTP 类型，以便使用 HttpServletRequest/HttpServletResponse 的特有方法。
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 提取客户端真实IP、账户信息，生成请求唯一标识。
        String ip = extractIp(httpRequest);
        String account = extractAccount(httpRequest);
        String requestId = UUID.randomUUID().toString();
        String uri = httpRequest.getRequestURI();
        // 连接ID由请求ID和IP拼接而成，确保唯一且可追踪。
        String connectionId = requestId + "@" + ip;

        // 将 requestId 和 clientIp 存入请求属性，供下游组件使用。
        httpRequest.setAttribute("requestId", requestId);
        httpRequest.setAttribute("clientIp", ip);

        // ========== 第1层防御：连接数限制 ==========
        // 连接数限制是最外层的防线。如果连连接都无法建立，后续所有检查都没有意义。
        if (!connectionManager.register(connectionId, ip)) {
            httpResponse.setStatus(503);
            httpResponse.getWriter().write("{\"error\":\"Service Unavailable\",\"message\":\"连接数已达上限\"}");
            return;
        }

        try {
            // ========== 第2层防御：速率限制 ==========
            // 根据配置模式（IP/ACCOUNT/BOTH）生成限流Key。
            String rateLimitKey = resolveRateLimitKey(ip, account);
            if (!rateLimiter.isAllowed(rateLimitKey)) {
                httpResponse.setStatus(429);
                // 设置 Retry-After 响应头，告知客户端在多少秒后重试（即封禁时长）。
                httpResponse.setHeader("Retry-After", String.valueOf(properties.getRateLimit().getBanDuration().getSeconds()));
                httpResponse.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"请求频率超限，已被临时封禁\"}");
                return;
            }

            // ========== 第3层防御：流量整形 ==========
            // 检查该URI是否为优先路径（如健康检查）。优先路径跳过令牌桶检查。
            boolean prioritized = trafficShaper.isPrioritizedPath(uri);
            if (!prioritized) {
                // 非优先路径需要获取令牌桶中的令牌，最多等待5秒。
                if (!trafficShaper.tryEnter(5000)) {
                    httpResponse.setStatus(503);
                    httpResponse.getWriter().write("{\"error\":\"Service Unavailable\",\"message\":\"服务器负载过高，请稍后重试\"}");
                    return;
                }
            }

            // ========== 第4层防御：队列管理 ==========
            // 根据账户或IP生成队列Key。有账户信息时优先按账户排队，否则按IP排队。
            String queueKey = resolveQueueKey(ip, account);
            if (!queueManager.tryAcquire(queueKey)) {
                httpResponse.setStatus(503);
                httpResponse.getWriter().write("{\"error\":\"Service Unavailable\",\"message\":\"请求队列已满\"}");
                return;
            }

            try {
                // ========== 第5层防御：慢请求跟踪 ==========
                // 慢请求跟踪不阻塞请求，仅在后台建立监控记录。
                Thread currentThread = Thread.currentThread();
                slowRequestDetector.track(requestId, ip, uri, currentThread);

                // ========== 执行业务逻辑 ==========
                // 判断请求是否支持异步处理（Servlet 3.0+ 的 AsyncContext）。
                if (httpRequest.isAsyncSupported()) {
                    chain.doFilter(request, response);
                    // 检查异步请求是否已经启动。
                    if (httpRequest.isAsyncStarted()) {
                        // 异步请求已启动，添加异步监听器，确保请求完成/超时/出错时都能正确停止跟踪。
                        httpRequest.getAsyncContext().addListener(
                            new SlowRequestDetector.MonitoringAsyncListener(slowRequestDetector, requestId));
                    } else {
                        // 同步完成，直接停止跟踪。
                        slowRequestDetector.complete(requestId);
                    }
                } else {
                    // 不支持异步，使用 try-finally 确保无论如何都会停止跟踪。
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        slowRequestDetector.complete(requestId);
                    }
                }
            } finally {
                // 无论业务逻辑成功或失败，都必须释放队列许可和令牌桶令牌。
                queueManager.release(queueKey);
                if (!prioritized) {
                    trafficShaper.leave();
                }
            }

        } catch (InterruptedException e) {
            // 如果请求在等待队列或令牌桶时被中断，重新设置中断标志并返回503。
            Thread.currentThread().interrupt();
            httpResponse.setStatus(503);
            httpResponse.getWriter().write("{\"error\":\"Service Unavailable\",\"message\":\"请求被中断\"}");
        } finally {
            // 无论请求结果如何，都必须注销连接，释放连接配额。
            connectionManager.unregister(connectionId, ip);
        }
    }

    /**
     * 从 HTTP 请求中提取客户端真实IP地址。
     * <p>
     * 按照以下优先级依次检查常见的代理转发头：
     * <ol>
     *   <li>{@code X-Forwarded-For} - 可能包含多个IP（经过多级代理），取第一个（最原始的客户端IP）。</li>
     *   <li>{@code Proxy-Client-IP} - 部分代理软件使用。</li>
     *   <li>{@code WL-Proxy-Client-IP} - WebLogic 代理使用。</li>
     *   <li>{@code X-Real-IP} - Nginx 常用。</li>
     *   <li>{@code request.getRemoteAddr()} - 最后的回退方案。</li>
     * </ol>
     * 如果 {@code X-Forwarded-For} 包含逗号分隔的多个IP，只取第一个（即最靠近客户端的IP）。
     * </p>
     *
     * @param request HTTP请求对象
     * @return 提取到的客户端IP地址
     */
    private String extractIp(HttpServletRequest request) {
        // 优先检查 X-Forwarded-For 头，这是 HTTP 标准中最常用的代理转发头。
        String ip = request.getHeader("X-Forwarded-For");
        // 如果该头不存在、为空或值为 "unknown"，则尝试下一个头。
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个IP，如 "client, proxy1, proxy2"，取第一个（真实客户端IP）。
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }

    /**
     * 从 HTTP 请求中提取用户账户标识。
     * <p>
     * 按照以下优先级尝试获取：
     * <ol>
     *   <li>{@code X-User-Id} 请求头 - 微服务间透传的用户ID。</li>
     *   <li>{@code request.getUserPrincipal()} - Spring Security 等框架的已认证主体。</li>
     *   <li>{@code X-API-Key} 请求头 - API Key 认证方式，返回格式为 {@code "api:xxx"}。</li>
     * </ol>
     * 如果都无法获取，返回 {@code null}，后续将回退到按IP限流。
     * </p>
     *
     * @param request HTTP请求对象
     * @return 账户标识字符串，如果未获取到则返回 {@code null}
     */
    private String extractAccount(HttpServletRequest request) {
        // 尝试从 X-User-Id 头获取。这是微服务架构中常见的用户ID透传方式。
        String user = request.getHeader("X-User-Id");
        if (user != null && !user.isEmpty()) {
            return user;
        }
        // 尝试从安全框架的 Principal 中获取用户名。
        if (request.getUserPrincipal() != null) {
            return request.getUserPrincipal().getName();
        }
        // 尝试从 X-API-Key 头获取，并添加 "api:" 前缀以区分普通用户名。
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return "api:" + apiKey;
        }
        return null;
    }

    /**
     * 根据配置模式生成速率限制的Key。
     * <p>
     * 支持的限流模式：
     * <ul>
     *   <li>{@code IP} - 始终使用IP作为限流Key，格式为 {@code "ip:xxx.xxx.xxx.xxx"}。</li>
     *   <li>{@code ACCOUNT} - 优先使用账户作为限流Key，格式为 {@code "acct:username"}；
     *       如果无法获取账户信息，则回退到IP限流。</li>
     *   <li>{@code BOTH} - 与 ACCOUNT 模式行为一致（当前实现），优先账户，无账户时回退IP。</li>
     * </ul>
     * </p>
     *
     * @param ip      客户端IP地址
     * @param account 提取到的账户标识（可能为 {@code null}）
     * @return 用于速率限制的Key字符串
     */
    private String resolveRateLimitKey(String ip, String account) {
        switch (properties.getRateLimit().getMode()) {
            case ACCOUNT:
                // 优先使用账户，无账户时回退到IP。
                return account != null ? "acct:" + account : "ip:" + ip;
            case BOTH:
                // 当前实现与 ACCOUNT 模式行为一致。
                return account != null ? "acct:" + account : "ip:" + ip;
            case IP:
            default:
                // 默认按IP限流。
                return "ip:" + ip;
        }
    }

    /**
     * 生成队列管理的Key。
     * <p>
     * 队列管理的维度与限流可以独立配置。
     * 当前实现逻辑：有账户信息时按账户排队（{@code "queue:acct:xxx"}），
     * 无账户时按IP排队（{@code "queue:ip:xxx"}）。
     * 这种设计允许同一IP下的不同账户各自拥有独立的并发配额，
     * 避免因一个IP下某个账户的高并发影响同一IP下的其他账户。
     * </p>
     *
     * @param ip      客户端IP地址
     * @param account 提取到的账户标识（可能为 {@code null}）
     * @return 用于队列管理的Key字符串
     */
    private String resolveQueueKey(String ip, String account) {
        // 队列可以按IP或账户独立分组。有账户时优先按账户排队。
        return account != null ? "queue:acct:" + account : "queue:ip:" + ip;
    }
}
