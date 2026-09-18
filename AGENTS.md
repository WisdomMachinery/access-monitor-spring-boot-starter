# AGENTS.md — access-monitor-spring-boot-starter

面向在本仓库工作的编码代理（Codex 等）。以下约定是硬性的，除用户明确要求外不要偏离。

## 仓库结构（最容易踩的坑，先看这里）

- **git 根目录是上级目录，不是本目录**：`F:\githubprojects\Access Monitor`；
  Maven 工程在子目录 `access-monitor-spring-boot-starter/`。
- 因此：`.github/` 必须位于**仓库根目录**（GitHub 只读取根目录下的 `.github/`），
  而 `pom.xml`、`mvnw`、`*.md` 在工程子目录里；命令要在工程子目录执行。
- 改流水线时不要只在子目录里找 `.github/`；改完后确认 `git ls-files --full-name | grep .github/`
  的路径是以 `.github/` 开头。

## 这是什么

Spring Boot **Starter（库，不是可执行应用）**：限流、请求排队、流量整形、慢请求检测、连接数监控。

## 构建与验证：一律使用 Maven Wrapper

- 构建 + 全部测试：`./mvnw -B --no-transfer-progress verify`
- 只跑某个测试：`./mvnw -B test -Dtest=TrafficShaperTest`
- 发布产物预演：`./mvnw -B -Prelease -DskipTests package`
- 工具链：JDK 17（Spring Boot 3.2 / `java.version=17`）。
- 不要用系统 `mvn`；不要修改 `.mvn/wrapper/maven-wrapper.properties` 里的
  `distributionUrl` 与 `distributionSha256Sum`。
- **改完代码必须实际执行 `verify`**，不要只做静态推断。

## 代码约定

- 库代码**禁止**加 `@Component` / `@Configuration`：Bean 一律由 `AccessMonitorAutoConfiguration` 以 `@Bean` 注册，
  否则使用方组件扫描到该包会出现重复注册（过滤器会被注册两次）。
- 产物必须是 thin jar；不要启用 `spring-boot-maven-plugin` 的 `repackage`。
  `spring-boot-starter-web` 保持 `optional`，不要把它变成强制传递依赖。
- Actuator 端点：Spring Boot 3 的操作路径由 **`@Selector` 参数**决定，**方法名不参与**；
  同一路径下不能有多个无选择器的操作，否则端点一旦暴露就会在启动期抛
  `Unable to map duplicate endpoint operations`。端点 Bean 必须由自动配置注册，
  并遵循 `management.endpoint.accessmonitor.enabled`。
- 配置属性用 `@ConfigurationProperties` + 内嵌静态类表达层级；**新增配置项必须同步 README 的配置表**。
- 边界值要想清楚：例如 `max-queue-size-per-key: 0` 必须表达为"不提供排队能力、直接拒绝"，
  而不是让 `LinkedBlockingQueue` 抛 `IllegalArgumentException` 退化成 HTTP 500。

## 测试约定

- 新增或修改对外行为必须补测试。
- 自动装配相关改动至少覆盖：Bean 注册、配置开关降级、非 Web 环境降级、属性绑定、
  用户自定义 Bean 覆盖、端点注册与关闭。

## 提交与发布

- 提交信息用 Conventional Commits + 中文正文，例：`fix: 修复 max-queue-size-per-key 为 0 时抛异常`。
- **不要擅自 `git commit` / `git push` / 打标签**，除非用户明确要求。
- 发布：标签版本必须与 `pom.xml` 的 `<version>` 完全一致（CI 校验）；发布成功后提升为下一个 `-SNAPSHOT`。
- 默认分支是 `main`；远端另有一条历史无关的旧分支 `Master`（项目曾在仓库根级），**不要**把它当作当前代码。

## 环境

- Windows 开发机：JDK `F:\jdk-17.0.12`，本地 Maven 仓库 `D:\repository`。
- 新增/编辑的文本文件**必须以换行结尾**（缺结尾换行曾导致 `mvnw` 在 Linux/CI 下解析失败）。

## Code Review Rules

### Actuator 端点映射

- 不得新增没有 `@Selector` 的 `@ReadOperation` / `@WriteOperation`：同一路径只能有一个无选择器的操作，
  否则端点一旦被暴露就会在启动期抛 `Unable to map duplicate endpoint operations`。
  Safe path：用 `@Selector` 参数区分路径；未知分段返回 404 并附带可用分段列表。

### 端点路径契约

- 不得破坏 `/actuator/accessmonitor` 及其 `section` / `record` 分段路径，使用方的探针与看板依赖它。
  Safe path：需要新视图时新增分段；废弃分段保留一个发布周期。

### 依赖传递

- 不得把 `spring-boot-starter-web` 从 `optional` 提升为强制传递依赖。
  Safe path：保持 `optional`，用 `@ConditionalOnClass` / `@ConditionalOnWebApplication` 在非 Web 环境降级。

### 配置边界语义

- 不得把配置边界值退化为异常：`max-queue-size-per-key: 0` 表示"不提供排队能力"，超限请求应直接拒绝（429），
  而不是让 `new LinkedBlockingQueue(0)` 抛 `IllegalArgumentException` 变成 HTTP 500。
  Safe path：创建队列前显式处理容量为 0 的分支。
