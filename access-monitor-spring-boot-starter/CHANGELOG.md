# 更新日志

本文件记录本项目的所有重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.0.0] - 2026-09-18

首个可用版本。本版本修复了此前导致 Starter 无法被正常依赖、监控端点无法访问的多个阻塞问题。

### 修复

- 修正 Actuator 端点无法访问的问题：端点 Bean 之前只靠 `@Component` 注册，使用方不会扫描该包，
  现已由自动配置注册，并遵循 `management.endpoint.accessmonitor.enabled` 与 actuator 的暴露配置。
- 修正端点路径模型：Spring Boot 3 的操作路径由 `@Selector` 参数决定而非方法名，
  原先 6 个无选择器的 `@ReadOperation` 方法都会映射到根路径，
  一旦端点被真正暴露就会在启动期抛出 `Unable to map duplicate endpoint operations`；
  现在由 `section` / `record` 两个带选择器的操作统一暴露子资源，未知分段返回 404。
- 修正 `max-queue-size-per-key: 0` 时抛 `IllegalArgumentException` 的问题
  （超限请求会退化成 HTTP 500），现在按「不提供排队能力」直接拒绝。
- `AccessMonitorApplication` 从 `src/main` 移到 `src/test`，不再打包进发布产物。
- 修复 CI / Dependabot 从不触发的问题：本仓库的 git 根在工程目录的上一级，
  而 `.github/` 原先位于 `access-monitor-spring-boot-starter/` 子目录内，GitHub 不会读取；
  现已移动到仓库根目录，并由流水线 `defaults.run.working-directory` 指向工程目录。
- 修复 `mvnw` 在 Linux 下无法读取 `distributionUrl` 的问题：`maven-wrapper.properties`
  末行缺少结尾换行时，POSIX sh 的 while read 在 EOF 处不会执行循环体，导致变量为空；
  已为缺结尾换行的文本文件补上换行，并增加 `distributionSha256Sum` 校验 Maven 分发包的完整性。

### 变更

- 禁用 `spring-boot-maven-plugin` 的 `repackage`：产物改为普通 thin jar，
  类位于 jar 根路径，可被其他项目作为依赖正常引用。
- 移除库类上的 `@Component`，Bean 统一由自动配置注册，避免使用方扫描到该包时出现重复 Bean
  （尤其是过滤器会被注册两次）。
- `spring-boot-starter-web` 改为 `optional`，不再强制传递给使用方。

### 新增

- 自动装配契约测试（Bean 注册、开关降级、非 Web 环境降级、属性绑定、用户 Bean 覆盖、端点注册）
  与核心组件单元测试，共 26 个用例。
- Maven Wrapper、`.gitignore`、`.gitattributes`、CI（JDK 17/21）、Dependabot 配置、Apache-2.0 LICENSE。
- `release` profile：生成 sources/javadoc jar 并发布到 GitHub Packages；
  `.github/workflows/release.yml` 校验标签与版本一致后发布。
