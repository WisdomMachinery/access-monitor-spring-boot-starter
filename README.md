# access-monitor-spring-boot-starter

Spring Boot 访问监控 / 限流 Starter：限流、请求排队、流量整形、慢请求检测与连接数监控。

## 仓库结构

```
.
├── .github/                          # CI 与发布流水线（GitHub 只认仓库根目录下的 .github）
└── access-monitor-spring-boot-starter/   # Maven 工程本体
    ├── pom.xml
    ├── mvnw / mvnw.cmd
    ├── src/
    └── README.md                     # 使用文档、配置项与发布流程
```

> GitHub Actions 只读取仓库根目录的 `.github/workflows`，且 Dependabot 的 `directory`
> 必须指向 `pom.xml` 所在目录，因此流水线配置在根目录、工程在子目录。

## 快速开始

```bash
cd access-monitor-spring-boot-starter
./mvnw verify
```

完整的使用说明、配置项与发布流程见
[access-monitor-spring-boot-starter/README.md](access-monitor-spring-boot-starter/README.md)。
