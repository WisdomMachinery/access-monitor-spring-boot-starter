# Access Monitor Spring Boot Starter - 访问监控与限流 Starter

## 一、项目概述

**Access Monitor Spring Boot Starter** 是一个专为 Spring Boot 应用设计的访问监控与限流防护 Starter。
它通过 Spring Boot 的自动装配机制，在应用启动时无缝集成多层防御体系，无需编写任何代码即可获得强大的访问控制能力。

本 Starter 的设计目标是帮助开发者轻松应对以下安全与性能挑战：

- **高频暴力请求**：同一IP或账户在短时间内发起大量请求，耗尽服务器资源。
- **突发流量洪峰**：全系统瞬时请求量激增，超出后端服务的处理能力。
- **慢连接攻击（Slowloris）**：攻击者以极慢的速度发送请求，长期占用连接和线程。
- **连接资源耗尽**：大量并发连接（包括僵尸连接）占满服务器的连接池和文件描述符。

---

## 二、功能特性

| 功能模块 | 核心能力 | 防御场景 |
|---|---|---|
| **速率限制（Rate Limiting）** | 按IP或账户统计1分钟内的请求次数，超阈值自动封禁 | 单客户端高频请求、暴力破解、爬虫抓取 |
| **请求队列（Request Queue）** | 对同一Key的并发请求进行排队管理，控制并发数 | 瞬时高并发、资源争抢、削峰填谷 |
| **流量整形（Traffic Shaping）** | 基于令牌桶算法的全局限速，支持优先路径 | 全系统突发流量、DDoS攻击 |
| **慢请求检测（Slow Request Detection）** | 跟踪请求处理时长，检测并自动中断挂死请求 | Slowloris攻击、后端阻塞、资源泄漏 |
| **连接数限制（Connection Limit）** | 控制单IP和全局的并发连接数，自动清理空闲连接 | 连接耗尽、僵尸连接、CC攻击 |
| **监控端点（Actuator Endpoint）** | 暴露 `/actuator/accessmonitor` 端点，实时查看状态 | 运维监控、故障排查、安全审计 |

---

## 三、快速开始

### 3.1 引入依赖

将本 Starter 作为 Maven 依赖引入到你的 Spring Boot 项目中：

```xml
<dependency>
    <groupId>com.access.monitor</groupId>
    <artifactId>access-monitor-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.2 添加配置

在项目的 `application.yml`（或 `application.properties`）中添加如下配置：

```yaml
access:
  monitor:
    enabled: true                      # 总开关，默认true
    rate-limit:
      enabled: true                    # 速率限制开关
      max-requests-per-minute: 20      # 每Key每分钟最大请求数
      ban-duration: 10m                # 超限后的封禁时长
      mode: IP                         # 限流模式：IP / ACCOUNT / BOTH
      whitelist:                       # 白名单（不受限流约束）
        - "127.0.0.1"
        - "::1"
      blacklist:                       # 黑名单（无条件拒绝）
        - "10.0.0.99"
    queue:
      enabled: true                    # 请求队列开关
      max-concurrent-per-key: 10       # 每Key最大并发处理数
      max-queue-size-per-key: 100      # 每Key最大排队长度
      queue-timeout: 30s               # 队列等待超时时间
    traffic-shaper:
      enabled: true                    # 流量整形开关
      global-rate-per-second: 1000     # 全局每秒产生令牌数
      burst-capacity: 2000             # 令牌桶容量（允许的最大突发流量）
      prioritized-paths:               # 优先路径（绕过令牌桶）
        - "/health"
        - "/actuator/health"
        - "/favicon.ico"
    slow-request:
      enabled: true                    # 慢请求检测开关
      threshold: 10s                   # 慢请求判定阈值
      hang-threshold: 60s              # 挂死请求判定阈值
      auto-interrupt: true             # 是否自动中断挂死线程
    connection-limit:
      enabled: true                    # 连接数限制开关
      max-connections-per-ip: 50       # 单IP最大连接数
      global-max-connections: 10000    # 全局最大连接数
      idle-timeout: 5m                 # 连接空闲超时时间
```

### 3.3 启动应用

完成上述两步后，直接启动你的 Spring Boot 应用即可。访问监控模块会自动生效，
所有进入系统的 HTTP 请求都会经过多层防御检查。

---

## 四、配置项详解

### 4.1 全局开关（access.monitor.enabled）

- **类型**：`boolean`
- **默认值**：`true`
- **说明**：整个访问监控模块的总开关。设置为 `false` 时，所有监控和限流逻辑完全失效，
  请求将直接通过，不做任何拦截。适用于需要临时关闭监控的紧急情况或本地开发调试。

### 4.2 速率限制（access.monitor.rate-limit）

#### enabled
- **类型**：`boolean`
- **默认值**：`true`
- **说明**：速率限制功能的独立开关。即使全局开关为 `true`，此项为 `false` 时也不会触发限流。

#### max-requests-per-minute
- **类型**：`int`
- **默认值**：`20`
- **说明**：每个Key在1分钟时间窗口内允许的最大请求次数。第 `(max + 1)` 次请求将触发封禁。
  建议根据业务特性设置：
  - 普通Web页面：10 ~ 30
  - API接口：50 ~ 200
  - 文件下载/上传：5 ~ 10

#### ban-duration
- **类型**：`Duration`
- **默认值**：`10m`（10分钟）
- **说明**：触发速率限制后的封禁时长。封禁期间，该Key的所有请求将直接返回 HTTP 429。
  支持的时间单位：`d`（天）、`h`（小时）、`m`（分钟）、`s`（秒）。

#### mode
- **类型**：`enum`（IP / ACCOUNT / BOTH）
- **默认值**：`IP`
- **说明**：限流的统计维度：
  - **IP**：按客户端IP地址限流。适合大多数场景，简单直观。
  - **ACCOUNT**：按登录账户限流。适合API服务，防止单个用户滥用接口。
  - **BOTH**：优先按账户限流，无账户信息时回退到IP限流。

#### whitelist
- **类型**：`List<String>`
- **默认值**：空列表
- **说明**：白名单中的IP或账户不受速率限制约束。常用于：
  - 内部服务地址（如监控探针、健康检查）
  - 负载均衡器/反向代理的IP
  - 测试环境IP
  - 管理后台的IP

#### blacklist
- **类型**：`List<String>`
- **默认值**：空列表
- **说明**：黑名单中的IP或账户将被无条件拒绝访问，即使请求频次很低。适用于已知恶意IP的紧急封禁。

### 4.3 请求队列（access.monitor.queue）

#### enabled
- **类型**：`boolean`
- **默认值**：`true`

#### max-concurrent-per-key
- **类型**：`int`
- **默认值**：`10`
- **说明**：每个Key（IP或账户）允许同时处理的最大请求数。超过此数的请求将进入队列排队。

#### max-queue-size-per-key
- **类型**：`int`
- **默认值**：`100`
- **说明**：每个Key的最大排队长度。当并发数已满且队列也满时，新请求将被直接拒绝。

#### queue-timeout
- **类型**：`Duration`
- **默认值**：`30s`
- **说明**：请求在队列中的最长等待时间。超过此时间仍未获得执行机会的请求将被移除并返回 HTTP 503。

### 4.4 流量整形（access.monitor.traffic-shaper）

#### enabled
- **类型**：`boolean`
- **默认值**：`true`

#### global-rate-per-second
- **类型**：`int`
- **默认值**：`1000`
- **说明**：令牌桶每秒产生的令牌数，即系统的平均处理速率上限。应根据服务器的实际处理能力设置。

#### burst-capacity
- **类型**：`int`
- **默认值**：`2000`
- **说明**：令牌桶的容量上限，即系统允许的最大突发流量。建议设置为 `global-rate-per-second` 的 1~3 倍。

#### prioritized-paths
- **类型**：`List<String>`
- **默认值**：空列表
- **说明**：优先路径列表，匹配到的请求将绕过令牌桶直接放行。配置时使用路径前缀匹配，
  例如 `/health` 会同时匹配 `/health`、`/health/live`、`/health/ready`。
  强烈建议至少将健康检查路径加入优先列表，否则高负载时探针可能误判服务不可用。

### 4.5 慢请求检测（access.monitor.slow-request）

#### enabled
- **类型**：`boolean`
- **默认值**：`true`

#### threshold
- **类型**：`Duration`
- **默认值**：`10s`
- **说明**：慢请求判定阈值。请求处理时间超过此值将输出 WARN 级别日志。建议根据接口的 P99 响应时间设置。

#### hang-threshold
- **类型**：`Duration`
- **默认值**：`60s`
- **说明**：挂死请求判定阈值。请求处理时间超过此值将输出 ERROR 级别日志。必须大于 `threshold`。

#### auto-interrupt
- **类型**：`boolean`
- **默认值**：`true`
- **说明**：是否自动中断挂死请求的处理线程。注意：此功能基于 Java 的线程中断机制（协作式），
  只有当目标代码正确处理了 {@code InterruptedException} 或检查了中断状态时，中断才会生效。
  如果业务代码完全忽略中断信号，线程仍可能继续运行。

### 4.6 连接数限制（access.monitor.connection-limit）

#### enabled
- **类型**：`boolean`
- **默认值**：`true`

#### max-connections-per-ip
- **类型**：`int`
- **默认值**：`50`
- **说明**：单个IP地址允许的最大并发连接数。
  **注意**：如果目标用户群来自 NAT 后的企业内网（多个用户共享一个公网IP），
  此值不宜设置过低，否则可能误拦截正常用户。

#### global-max-connections
- **类型**：`int`
- **默认值**：`10000`
- **说明**：系统全局允许的最大并发连接数。应根据服务器的文件描述符上限（{@code ulimit -n}）
  和内存容量合理设置。通常建议设置为文件描述符上限的 70%~80%。

#### idle-timeout
- **类型**：`Duration`
- **默认值**：`5m`
- **说明**：连接的空闲超时时间。超过此时间未发送新请求的连接将被视为僵尸连接并从内部计数器中移除，
  释放配额供新连接使用。注意：此清理动作不会直接断开 TCP 连接。

---

## 五、工作原理详解

### 5.1 请求处理流程

当一次 HTTP 请求到达时，{@link AccessMonitorFilter} 会按照以下顺序执行检查：

```
HTTP Request
    |
    v
[1] 连接数限制检查 (ConnectionManager)
    |-- 全局连接数超限? --YES--> 返回 HTTP 503
    |-- 单IP连接数超限? --YES--> 返回 HTTP 503
    |
    v
[2] 速率限制检查 (RateLimiter)
    |-- 黑名单? --YES--> 返回 HTTP 429
    |-- 白名单? --YES--> 跳过
    |-- 已封禁? --YES--> 返回 HTTP 429 (带 Retry-After)
    |-- 窗口内超限? --YES--> 封禁并返回 HTTP 429
    |
    v
[3] 流量整形检查 (TrafficShaper)
    |-- 优先路径? --YES--> 跳过
    |-- 令牌桶空? --YES--> 返回 HTTP 503
    |
    v
[4] 队列管理检查 (RequestQueueManager)
    |-- 并发未满? --YES--> 直接放行
    |-- 队列未满? --YES--> 入队等待
    |-- 队列已满? --YES--> 返回 HTTP 503
    |
    v
[5] 慢请求跟踪 (SlowRequestDetector)
    |-- 建立跟踪记录，启动定时检查任务
    |
    v
执行业务逻辑 (chain.doFilter)
    |
    v
请求完成，释放所有资源
```

### 5.2 速率限制算法

采用**固定时间窗口算法**，以 1 分钟为一个统计周期：

1. 每个Key维护一个计数器（{@code requestCount}）和一个窗口起始时间（{@code firstRequestTime}）。
2. 新请求到达时，先检查窗口是否已过期（当前时间 - 起始时间 > 1分钟）。
3. 如果窗口过期，重置计数器为0，更新起始时间为当前时间，开始新窗口。
4. 将计数器原子自增1。
5. 如果计数器超过阈值，立即对该Key执行封禁，设置 {@code bannedUntil = 当前时间 + banDuration}。
6. 在封禁期间，所有请求直接拒绝；封禁到期后自动解封。

**优点**：实现简单、性能高、内存占用少。
**局限**：在窗口边界处可能存在少量计数翻倍的问题（如窗口最后一秒和下一秒分别发送阈值数量的请求）。
对于绝大多数Web场景，这种精度已足够。如需更精确，可升级为滑动窗口或令牌桶算法。

### 5.3 请求队列机制

对每个Key维护独立的 {@link java.util.concurrent.Semaphore} 和 {@link java.util.concurrent.BlockingQueue}：

- **Semaphore**：控制同时处理的请求数（许可数 = {@code maxConcurrentPerKey}）。
- **BlockingQueue**：存放等待执行的请求（容量 = {@code maxQueueSizePerKey}）。

当请求处理完成时，优先唤醒队列中的等待请求（FIFO），而非让新到达的请求抢占资源。

### 5.4 令牌桶流量整形

维护一个全局 {@link Semaphore} 作为令牌桶：

- 初始时桶是满的，有 {@code burstCapacity} 个令牌。
- 每秒由独立的后台线程补充令牌，补充数量 = {@code min(globalRatePerSecond, 桶剩余空间)}。
- 每个请求进入时从桶中获取一个令牌，处理完成后归还。
- 优先路径的请求不消耗令牌。

这种机制既限制了平均速率，又允许一定程度的突发流量。

### 5.5 慢请求检测

为每个请求创建 {@link SlowRequestDetector.TrackedRequest} 跟踪记录，并调度两个定时任务：

- **Threshold 检查**：在请求进入后 {@code threshold} 时间触发。如果请求仍未完成，输出 WARN 日志。
- **Hang 检查**：在请求进入后 {@code hangThreshold} 时间触发。如果请求仍未完成，输出 ERROR 日志，
  并可选择向处理线程发送 {@code interrupt()} 信号。

对于异步请求（Servlet 3.0+ AsyncContext），通过 {@link SlowRequestDetector.MonitoringAsyncListener}
确保请求在任何结束场景（完成、超时、错误）下都能正确停止跟踪。

---

## 六、注意事项与最佳实践

### 6.1 反向代理环境下的IP获取

如果你的应用部署在 Nginx、SLB、CDN 等反向代理之后，必须确保代理正确传递客户端真实IP：

**Nginx 配置示例**：
```nginx
location / {
    proxy_pass http://backend;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Real-IP $remote_addr;
}
```

如果代理配置不正确，{@code extractIp} 方法可能获取到代理服务器的IP，导致：
- 所有请求被视为来自同一个IP，限流失效或误拦截。
- 白名单/黑名单无法正确匹配。

### 6.2 NAT 环境下的连接数限制

在以下场景中，多个真实用户可能共享同一个公网IP：
- 企业内网通过 NAT 上网
- 移动蜂窝网络
- 校园网、公共WiFi

此时如果将 {@code max-connections-per-ip} 或 {@code max-requests-per-minute} 设置过低，
可能导致大量正常用户被误拦截。建议：
- 对内网应用（用户可识别）使用 **ACCOUNT** 模式按账户限流。
- 对外网应用（用户不可识别）使用 **IP** 模式，但阈值适当放宽。
- 监控日志中的 WARN 和 ERROR，及时发现误拦截情况。

### 6.3 线程中断的局限性

{@code auto-interrupt: true} 发送的是 Java 的线程中断信号，这是一种**协作式**中断：

- 如果业务代码在执行 {@code Thread.sleep()}、IO操作、{@code Object.wait()} 等可中断操作时，
  会抛出 {@link InterruptedException}，此时可以优雅退出。
- 如果业务代码处于纯计算循环（如死循环）且没有检查中断状态，线程将继续运行。
- 如果业务代码捕获了 {@code InterruptedException} 但没有正确处理（如只是打印日志后继续），
  线程也不会退出。

**建议**：确保你的业务代码正确处理线程中断，特别是在调用第三方库或执行长时间任务时。

### 6.4 性能影响

本 Starter 在设计上追求最小性能开销：

- 使用 {@link ConcurrentHashMap} 和原子变量避免全局锁。
- 对单个Key的操作使用细粒度同步（{@code synchronized(record)}），不同Key之间互不阻塞。
- 优先路径的请求绕过令牌桶，几乎零额外开销。
- 慢请求检测采用异步定时任务，不阻塞请求处理主线程。

在高并发场景下，主要的开销来自：
- 每次请求的 IP 解析（读取请求头）。
- 每次请求的 Map 查找和原子操作。
- 定时任务的周期性扫描。

在常规Web应用中，这些开销通常在微秒级别，对整体性能影响极小。

### 6.5 与 Spring Security 的集成

如果你的应用使用了 Spring Security，{@code AccessMonitorFilter} 默认在过滤器链的最前端执行
（{@code @Order(Ordered.HIGHEST_PRECEDENCE + 10)}）。这意味着：

- 优点：即使未经认证的请求也会被限流和监控，防止匿名攻击。
- 注意：如果某些安全端点（如登录页面）需要特殊处理，可通过配置白名单或优先路径放行。

### 6.6 监控端点的安全性

{@code /actuator/accessmonitor} 端点暴露了系统的实时运行状态，包括：
- 当前被封禁的IP列表和解封时间
- 当前正在执行的慢请求列表
- 当前全局连接数和令牌余量

**强烈建议**：
- 通过 Spring Security 限制只有管理员角色可以访问 Actuator 端点。
- 或者将 Actuator 配置到独立的管理端口，并限制该端口的网络访问（如仅允许内网访问）：

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health,info,accessmonitor
```

### 6.7 配置热更新

当前版本不支持配置热更新（运行时动态修改配置）。所有配置在应用启动时读取并初始化。
如果需要调整参数（如放宽限流阈值），需要修改配置文件后重启应用。

如需热更新能力，可考虑：
- 使用 Spring Cloud Config 配合 {@code @RefreshScope}（需要额外开发）。
- 通过 Actuator 端点暴露管理接口，手动触发配置刷新（需要额外开发）。

---

## 七、常见问题排查

### Q1: 为什么我的请求被返回了 429？

**可能原因**：
1. 该IP/账户在1分钟内的请求次数超过了 {@code max-requests-per-minute} 阈值，已被封禁。
2. 该IP/账户在黑名单中。

**排查方法**：
- 查看响应头中的 {@code Retry-After}，它告诉你还需要等待多少秒。
- 访问 {@code /actuator/accessmonitor/record/ip:你的IP} 查看详细记录。
- 检查日志中是否有 "速率超限触发封禁" 的记录。

### Q2: 为什么我的请求被返回了 503？

**可能原因**：
1. 连接数超限（全局或单IP）。
2. 令牌桶耗尽（全系统负载过高）。
3. 请求队列已满。

**排查方法**：
- 访问 {@code /actuator/accessmonitor} 查看各子系统的状态。
- 检查 {@code connections.globalActive} 是否接近 {@code globalMaxConnections}。
- 检查 {@code trafficShaper.availablePermits} 是否为 0。

### Q3: 为什么限流对某个IP不生效？

**可能原因**：
1. 该IP在白名单中。
2. 应用部署在代理后面，但代理没有传递 {@code X-Forwarded-For} 头，
   导致系统看到的是代理IP而非真实客户端IP。
3. 限流模式设置为 ACCOUNT，但请求未携带账户信息，导致系统回退到IP限流，
   而多个用户共享同一个代理IP。

### Q4: 慢请求检测的 auto-interrupt 为什么没效果？

**可能原因**：
1. 目标代码没有正确处理 {@link InterruptedException}（如捕获后吞掉了异常）。
2. 目标代码处于不可中断的阻塞操作中（如某些原生IO操作、数据库死锁等待）。
3. 目标代码处于纯计算循环中，没有检查 {@code Thread.interrupted()}。

**建议**：检查业务代码，确保长时间运行的任务能够响应线程中断。

### Q5: 为什么连接数限制看起来不准？

**可能原因**：
1. {@code ConnectionManager} 的计数器清理不会直接断开TCP连接，只是从内部统计中移除。
   如果客户端保持长连接（HTTP Keep-Alive），同一个TCP连接上的后续请求仍会重新触发计数。
2. 单IP连接数的判断存在微小的竞态窗口（先读取再判断再自增），在极端并发下可能短暂超出上限1~2个。

---

## 八、Actuator 端点响应示例

### GET /actuator/accessmonitor

```json
{
  "enabled": true,
  "rateLimit": {
    "enabled": true,
    "mode": "IP",
    "maxRequestsPerMinute": 20,
    "banDuration": "PT10M",
    "activeRecords": 15,
    "bannedKeys": {
      "ip:192.168.1.100": "2026-04-19T14:30:00Z"
    }
  },
  "queue": {
    "enabled": true,
    "maxConcurrentPerKey": 10,
    "maxQueueSizePerKey": 100
  },
  "trafficShaper": {
    "enabled": true,
    "globalRatePerSecond": 1000,
    "burstCapacity": 2000,
    "availablePermits": 1850
  },
  "slowRequests": {
    "enabled": true,
    "threshold": "PT10S",
    "hangThreshold": "PT1M",
    "activeTrackedRequests": 3,
    "requests": {
      "request-id-1": {
        "ip": "192.168.1.50",
        "uri": "/api/report",
        "elapsedMs": 15000
      }
    }
  },
  "connections": {
    "enabled": true,
    "globalMaxConnections": 10000,
    "maxConnectionsPerIp": 50,
    "globalActive": 120
  }
}
```

### GET /actuator/accessmonitor/record/ip:192.168.1.100

```json
{
  "key": "ip:192.168.1.100",
  "requestCount": 21,
  "banned": true,
  "bannedUntil": "2026-04-19T14:30:00Z",
  "firstRequestTime": "2026-04-19T14:19:30Z"
}
```

---

## 九、HTTP 响应码说明

| 状态码 | 含义 | 触发场景 |
|---|---|---|
| **429** | Too Many Requests | 速率限制超限或Key被封禁 |
| **503** | Service Unavailable | 连接数超限、队列已满、令牌桶耗尽、请求被中断 |

---

## 十、版本与兼容性

- **Java 版本**：17+
- **Spring Boot 版本**：3.2.0+
- **Servlet 规范**：3.1+（支持异步请求）

---

## 十一、开源协议

本项目采用 Apache License 2.0 开源协议。
