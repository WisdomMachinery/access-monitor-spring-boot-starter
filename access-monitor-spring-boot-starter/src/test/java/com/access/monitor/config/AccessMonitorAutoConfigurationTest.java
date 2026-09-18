package com.access.monitor.config;

import com.access.monitor.core.ConnectionManager;
import com.access.monitor.core.RateLimiter;
import com.access.monitor.core.RequestQueueManager;
import com.access.monitor.core.SlowRequestDetector;
import com.access.monitor.core.TrafficShaper;
import com.access.monitor.endpoint.AccessMonitorEndpoint;
import com.access.monitor.filter.AccessMonitorFilter;
import com.access.monitor.properties.AccessMonitorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配契约测试。
 * <p>
 * 对 Starter 而言，自动装配本身就是对外 API：这里覆盖 Bean 注册、开关降级、
 * 非 Web 环境降级、配置属性绑定和用户自定义 Bean 覆盖五种关键场景。
 * </p>
 */
class AccessMonitorAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AccessMonitorAutoConfiguration.class));

    @Test
    void registersAllComponentsInServletWebApplication() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(AccessMonitorFilter.class);
            assertThat(context).hasSingleBean(RateLimiter.class);
            assertThat(context).hasSingleBean(RequestQueueManager.class);
            assertThat(context).hasSingleBean(TrafficShaper.class);
            assertThat(context).hasSingleBean(SlowRequestDetector.class);
            assertThat(context).hasSingleBean(ConnectionManager.class);
            assertThat(context).hasSingleBean(AccessMonitorProperties.class);
        });
    }

    @Test
    void backsOffWhenExplicitlyDisabled() {
        webRunner.withPropertyValues("access.monitor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AccessMonitorFilter.class);
                    assertThat(context).doesNotHaveBean(RateLimiter.class);
                });
    }

    @Test
    void backsOffOutsideServletWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AccessMonitorAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AccessMonitorFilter.class);
                    assertThat(context).doesNotHaveBean(RateLimiter.class);
                });
    }

    @Test
    void bindsConfigurationPropertiesFromConsumerEnvironment() {
        webRunner.withPropertyValues(
                        "access.monitor.rate-limit.max-requests-per-minute=3",
                        "access.monitor.rate-limit.mode=ACCOUNT",
                        "access.monitor.queue.max-concurrent-per-key=7")
                .run(context -> {
                    AccessMonitorProperties properties = context.getBean(AccessMonitorProperties.class);
                    assertThat(properties.getRateLimit().getMaxRequestsPerMinute()).isEqualTo(3);
                    assertThat(properties.getRateLimit().getMode())
                            .isEqualTo(AccessMonitorProperties.RateLimit.Mode.ACCOUNT);
                    assertThat(properties.getQueue().getMaxConcurrentPerKey()).isEqualTo(7);
                });
    }

    @Test
    void userDefinedBeanTakesPrecedenceOverAutoConfiguration() {
        webRunner.withUserConfiguration(CustomRateLimiterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class))
                            .isSameAs(context.getBean("customRateLimiter"));
                });
    }

    @Test
    void registersActuatorEndpointByDefault() {
        webRunner.run(context -> assertThat(context).hasSingleBean(AccessMonitorEndpoint.class));
    }

    @Test
    void backsOffEndpointWhenExplicitlyDisabled() {
        webRunner.withPropertyValues("management.endpoint.accessmonitor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AccessMonitorEndpoint.class);
                    assertThat(context).hasSingleBean(AccessMonitorFilter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRateLimiterConfiguration {

        @Bean
        RateLimiter customRateLimiter(AccessMonitorProperties properties) {
            return new RateLimiter(properties);
        }
    }
}
