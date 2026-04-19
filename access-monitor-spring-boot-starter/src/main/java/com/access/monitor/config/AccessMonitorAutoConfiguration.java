package com.access.monitor.config;

import com.access.monitor.core.*;
import com.access.monitor.filter.AccessMonitorFilter;
import com.access.monitor.properties.AccessMonitorProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 访问监控模块的自动配置类。
 * <p>
 * 这是 Spring Boot Starter 的核心机制所在。该类利用 Spring Boot 的<strong>自动装配（Auto-Configuration）</strong>
 * 特性，在应用启动时自动检测环境条件，如果满足条件则自动创建并注册所有监控组件到 Spring 容器中。
 * 用户只需在 {@code pom.xml} 中引入本 Starter 依赖，无需编写任何配置类即可获得完整的访问监控能力。
 * </p>
 * <p>
 * <strong>类级条件注解说明：</strong>
 * <ul>
 *   <li>{@code @AutoConfiguration} - 标识这是一个自动配置类，Spring Boot 会在启动时扫描并处理它。
     *       需要在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 文件中
     *       注册该类的全限定名。</li>
   *   <li>{@code @ConditionalOnWebApplication(type = Type.SERVLET)} - 仅在基于 Servlet 的 Web 应用环境下生效。
     *       如果应用是 WebFlux（响应式Web），则此自动配置不会加载，避免不必要的组件创建。</li>
   *   <li>{@code @ConditionalOnClass(jakarta.servlet.Filter.class)} - 确保类路径中存在 {@link jakarta.servlet.Filter}
     *       类时才启用。这是一个防御性检查，防止在缺少 Servlet API 的环境中报错。</li>
   *   <li>{@code @EnableConfigurationProperties(AccessMonitorProperties.class)} - 启用配置属性绑定，
     *       使 {@link AccessMonitorProperties} 类可以与 {@code application.yml} 中的配置项自动映射。</li>
   *   <li>{@code @ConditionalOnProperty(prefix = "access.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)} -
     *       只有当配置项 {@code access.monitor.enabled=true} 时才启用自动配置。
     *       {@code matchIfMissing = true} 表示如果用户未配置该属性，默认视为 {@code true}（即默认开启）。</li>
 * </ul>
 * </p>
 * <p>
 * <strong>方法级条件注解说明：</strong>
 * 每个 {@code @Bean} 方法上都标注了 {@code @ConditionalOnMissingBean}，
 * 这意味着：如果用户的 Spring 容器中已经存在同类型的 Bean（例如用户自定义了一个 {@code RateLimiter}），
 * 则自动配置不会覆盖用户的自定义实现。这遵循 Spring Boot 的<strong>"约定优于配置，但配置优先于约定"</strong>原则。
 * </p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(jakarta.servlet.Filter.class)
@EnableConfigurationProperties(AccessMonitorProperties.class)
@ConditionalOnProperty(prefix = "access.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccessMonitorAutoConfiguration {

    /**
     * 创建并注册速率限制器 Bean。
     * <p>
     * {@link RateLimiter} 负责按IP或账户维度统计请求频率，并在超过阈值时执行封禁。
     * </p>
     *
     * @param properties 配置属性，注入已绑定的 {@link AccessMonitorProperties} 实例
     * @return 速率限制器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(AccessMonitorProperties properties) {
        return new RateLimiter(properties);
    }

    /**
     * 创建并注册请求队列管理器 Bean。
     * <p>
     * {@link RequestQueueManager} 负责控制同一Key的并发请求数，对超出的请求进行排队管理。
     * </p>
     *
     * @param properties 配置属性
     * @return 请求队列管理器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RequestQueueManager requestQueueManager(AccessMonitorProperties properties) {
        return new RequestQueueManager(properties);
    }

    /**
     * 创建并注册流量整形器 Bean。
     * <p>
     * {@link TrafficShaper} 采用令牌桶算法对全系统的请求速率进行全局控制和平滑。
     * </p>
     *
     * @param properties 配置属性
     * @return 流量整形器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficShaper trafficShaper(AccessMonitorProperties properties) {
        return new TrafficShaper(properties);
    }

    /**
     * 创建并注册慢请求检测器 Bean。
     * <p>
     * {@link SlowRequestDetector} 负责跟踪每个请求的处理时长，检测并告警慢请求和挂死请求。
     * </p>
     *
     * @param properties 配置属性
     * @return 慢请求检测器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SlowRequestDetector slowRequestDetector(AccessMonitorProperties properties) {
        return new SlowRequestDetector(properties);
    }

    /**
     * 创建并注册连接管理器 Bean。
     * <p>
     * {@link ConnectionManager} 负责控制系统层面的单IP和全局并发连接数上限。
     * </p>
     *
     * @param properties 配置属性
     * @return 连接管理器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionManager connectionManager(AccessMonitorProperties properties) {
        return new ConnectionManager(properties);
    }

    /**
     * 创建并注册访问监控过滤器 Bean。
     * <p>
     * {@link AccessMonitorFilter} 是整个访问监控模块的入口，它串联了上述所有组件，
     * 在请求处理链的最前端执行多层防御检查。
     * </p>
     * <p>
     * 该方法注入了前面创建的所有核心组件，通过构造方法传递给过滤器，
     * 确保各组件之间的依赖关系由 Spring 容器统一管理。
     * </p>
     *
     * @param properties            配置属性
     * @param rateLimiter           速率限制器
     * @param queueManager          请求队列管理器
     * @param trafficShaper         流量整形器
     * @param slowRequestDetector   慢请求检测器
     * @param connectionManager     连接管理器
     * @return 访问监控过滤器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessMonitorFilter accessMonitorFilter(AccessMonitorProperties properties,
                                                    RateLimiter rateLimiter,
                                                    RequestQueueManager queueManager,
                                                    TrafficShaper trafficShaper,
                                                    SlowRequestDetector slowRequestDetector,
                                                    ConnectionManager connectionManager) {
        return new AccessMonitorFilter(properties, rateLimiter, queueManager, trafficShaper, slowRequestDetector, connectionManager);
    }
}
