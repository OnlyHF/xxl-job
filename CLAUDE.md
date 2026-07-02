# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

XXL-JOB 是一个分布式任务调度框架，版本 3.4.0.study，基于 Java 17 / Spring Boot 4.0.5 / Netty 4.2.x / Groovy 5.x。

三个 Maven 模块：
- **xxl-job-core**: 执行器核心库（无 Spring Boot 依赖，`spring-context` 为可选）
- **xxl-job-admin**: 调度中心 Spring Boot 应用，依赖 `xxl-job-core`
- **xxl-job-executor-samples**: 三种示例执行器（springboot、frameless、springboot-ai）

## 常用开发命令

```bash
# 构建所有模块（跳过测试）
mvn clean package -DskipTests

# 构建特定模块
mvn clean package -pl xxl-job-core -DskipTests
mvn clean package -pl xxl-job-admin -DskipTests

# 运行测试
mvn test -pl xxl-job-admin
mvn test -Dtest=JobInfoControllerTest -pl xxl-job-admin

# 运行管理中心
cd xxl-job-admin && mvn spring-boot:run
```

首次运行需初始化数据库：`mysql -u root -p < doc/db/tables_xxl_job.sql`

## 架构概览

### 通信协议（双向 HTTP）

管理中心与执行器之间使用 `HttpTool` 生成动态 Java 代理，每个接口方法调用映射为 `POST /{methodName}`，请求/响应体均为 Gson 序列化的 JSON，鉴权通过 HTTP 头 `XXL-JOB-ACCESS-TOKEN`。

**管理中心 → 执行器**（`ExecutorBiz` 接口，执行器 Netty 服务器接收）：
- `/beat`、`/idleBeat`、`/run`、`/kill`、`/log`

**执行器 → 管理中心**（`AdminBiz` 接口，`OpenApiController` 在 `/api/{uri}` 接收）：
- `/api/callback`、`/api/registry`、`/api/registryRemove`

### 调度中心核心流程

`XxlJobAdminBootstrap`（`@Component`）是中心单例，负责按顺序启动和停止所有六个守护线程。

**时间环调度器**（`JobScheduleHelper`）：
- `scheduleThread`：每秒醒来，通过 `XxlJobLockMapper.scheduleLock()`（`SELECT FOR UPDATE`）获取 DB 分布式锁防止多管理中心重复触发，预读 5s 内到期的任务
- `ringThread`：维护一个 60 槽 `ConcurrentHashMap<Integer, List<Integer>>`（键为分钟内的秒数），每秒读当前秒及前两槽（容忍处理延迟），调用触发池执行

**双触发线程池隔离**（`JobTriggerPoolHelper`）：
- 默认路由到 `fastTriggerPool`
- 某 Job 每分钟触发耗时 >500ms 超过 10 次，自动降级到 `slowTriggerPool`，防止慢任务拖累快任务
- 计数器每分钟原子清零

**触发链路**（`JobTrigger.trigger()`）：加载 `XxlJobInfo`+`XxlJobGroup` → 路由选择执行器地址 → 写 `XxlJobLog` 行 → 构建 `TriggerRequest` → 调用 `ExecutorBiz.run()`

### 执行器核心流程

**`EmbedServer`**（Netty）：Netty Pipeline = `IdleStateHandler(90s)` → `HttpServerCodec` → `HttpObjectAggregator(5MB)` → `EmbedHttpServerHandler`。绑定成功后触发 `ExecutorRegistryThread.start()`。

**`JobThread`**：每个 jobId 一个线程，内部是 `LinkedBlockingQueue<TriggerRequest>`，空闲超过 30 次轮询（共约 90s）后自动销毁。每次执行后无论成功失败都向 `TriggerCallbackThread` 推送 `CallbackRequest`。

**回调持久化**（`TriggerCallbackThread`）：失败的回调序列化为 JSON 写入 `{logPath}/callbacklog/xxl-job-callback-{md5}.log`，由重试线程每 30s 重放，保证管理中心宕机期间回调不丢失。

### Handler 类型分发（`ExecutorBizImpl.run()`）

`glueType` 决定 Handler 来源：
- `BEAN`：从 `jobHandlerRepository` 按名称查找注册的 `IJobHandler`
- `GLUE_GROOVY`：通过 `GlueFactory`（`GroovyClassLoader`）编译缓存，包装为 `GlueJobHandler`；`glueUpdatetime` 变化时杀死旧线程重建
- Script 类型（`GLUE_SHELL/PYTHON/NODEJS/PHP/POWERSHELL`）：将源码写入临时文件，通过 `ScriptUtil.execToFile()` 启动 OS 进程执行

### Spring 执行器自动注册

`XxlJobSpringExecutor` 实现 `SmartInitializingSingleton`，在所有 Spring Bean 初始化完毕后扫描 `@XxlJob` 注解方法，包装为 `MethodJobHandler` 注册到 `jobHandlerRepository`，再调用 `super.start()`。

### 路由策略

10 种路由策略均继承 `ExecutorRouter`，在 `ExecutorRouteStrategyEnum` 枚举中注册。`SHARDING_BROADCAST` 不经路由器，在 `JobTrigger` 中直接对所有地址各触发一次（传入 shardIndex/shardTotal）。

### 上下文传播

`XxlJobContext` 用 `InheritableThreadLocal` 存储，Job Handler 内部创建的子线程也能调用 `XxlJobHelper.log()` 和返回结果。

### 子任务链

`XxlJobInfo.childJobId` 为逗号分隔的 jobId 列表，`JobCompleter.processChildJob()` 在父任务成功后以 `TriggerTypeEnum.PARENT` 依次触发，形成 DAG 式工作流。

## 关键扩展点

**新增路由策略**：
1. 继承 `ExecutorRouter`，实现 `route(TriggerParam, List<String>) -> ReturnT<String>`
2. 在 `ExecutorRouteStrategyEnum` 添加枚举值
3. 更新管理中心 UI 下拉选项

**新增任务处理器**：在 Spring 执行器中用 `@XxlJob("handlerName")` 注解方法即可（init/destroy 通过注解属性指定方法名）

**新增告警渠道**：实现 `JobAlarm` 接口并声明为 Spring Bean，`JobAlarmer` 会自动发现并调用

## 数据库核心表

| 表名 | 用途 |
|------|------|
| `xxl_job_info` | 任务定义，策略字段存储枚举 name 字符串 |
| `xxl_job_group` | 执行器组，`addressType=0` 为自动注册，`1` 为手动 |
| `xxl_job_log` | 执行日志，含 trigger 侧和 handle 侧两组状态码 |
| `xxl_job_registry` | 执行器心跳注册，>90s 未更新视为下线 |
| `xxl_job_lock` | 单行表，用于 `SELECT FOR UPDATE` 分布式锁 |
| `xxl_job_log_report` | 按日聚合的触发统计 |
| `xxl_job_logglue` | GLUE 源码版本历史 |

## 配置要点

**管理中心** (`xxl-job-admin/src/main/resources/application.properties`)：
- `xxl.job.accessToken` — 与执行器必须一致
- `xxl.job.triggerpool.fast.max` / `xxl.job.triggerpool.slow.max` — 触发线程池上限
- 默认端口 8080，上下文路径 `/xxl-job-admin`

**执行器**（`XxlJobExecutor` 配置属性）：
- `adminAddresses`、`appname`、`port`（默认 9999）、`accessToken`、`logPath`、`logRetentionDays`

## 注意事项

- `xxl-job-core` 的 `util/deprecated/` 包含旧版 `ReturnT`、`AdminBizClient` 等遗留代码，新代码使用 `com.xxl.tool.*` 对应实现
- 执行器注册心跳间隔 30s，管理中心 90s 超时下线；调度器 `DEAD_TIMEOUT = BEAT_TIMEOUT × 3`
- 管理中心多实例部署时，调度锁保证同一任务只被一个实例触发
