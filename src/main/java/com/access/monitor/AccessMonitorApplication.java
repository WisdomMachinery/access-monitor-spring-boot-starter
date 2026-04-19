package com.access.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 访问监控 Starter 的启动类。
 * <p>
 * 该类主要用于本地开发和测试场景。在将本模块作为 Starter 引入其他 Spring Boot 项目时，
 * 不需要使用这个启动类，而是由引入方项目的启动类负责启动 Spring 容器。
 * </p>
 * <p>
 * {@code @SpringBootApplication} 是一个组合注解，等价于：
 * <ul>
 *   <li>{@code @Configuration} - 声明该类是一个配置类。</li>
 *   <li>{@code @EnableAutoConfiguration} - 启用 Spring Boot 的自动配置机制。</li>
 *   <li>{@code @ComponentScan} - 自动扫描当前包及其子包下的所有 Spring 组件。</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
public class AccessMonitorApplication {

    /**
     * 应用程序的入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AccessMonitorApplication.class, args);
    }
}
