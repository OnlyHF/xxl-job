# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 项目概述

XXL-JOB 是一个分布式任务调度框架，由三个主要模块组成：
- **xxl-job-core**: 执行器核心库
- **xxl-job-admin**: 管理中心（调度器 UI 和 API）
- **xxl-job-executor-samples**: 执行器示例实现

## 常用开发命令

### 构建项目
```bash
# 构建所有模块
mvn clean package

# 构建特定模块
mvn clean package -pl xxl-job-admin
mvn clean package -pl xxl-job-core

# 跳过测试构建
mvn clean package -DskipTests
```

### 运行管理中心
```bash
# 数据库初始化（仅首次）
mysql -u root -p < doc/db/tables_xxl_job.sql

# 在 xxl-job-admin/src/main/resources/application.properties 中配置数据库连接
# 然后运行：
java -jar xxl-job-admin/target/xxl-job-admin-3.4.0.jar

# 或直接从 Maven 运行
cd xxl-job-admin
mvn spring-boot:run
```

### 运行测试
```bash
# 运行所有测试
mvn test

# 运行特定模块测试
mvn test -pl xxl-job-admin
mvn test -pl xxl-job-core

# 运行特定测试类
mvn test -Dtest=JobInfoControllerTest
```

## 架构概览

### 核心组件

**管理中心 (`xxl-job-admin`)**
- 主应用程序：`XxlJobAdminApplication.java`
- 调度器：`com.xxl.job.admin.scheduler` 包
  - `trigger/`: 任务触发逻辑
  - `route/`: 路由策略（第一个、最后一个、轮询、哈希等）
  - `cron/`: Cron 表达式解析
  - `misfire/`: 过期策略处理
- 控制器：`com.xxl.job.admin.controller` 包
- 数据层：MyBatis mappers 在 `com.xxl.job.admin.mapper` 包

**核心库 (`xxl-job-core`)**
- 执行器：`XxlJobExecutor` - 执行器实现的基类
- 内嵌服务器：`EmbedServer` - 基于 Netty 的 HTTP 服务器，用于与管理中心通信
- 任务处理器：`com.xxl.job.core.handler` 包
  - `IJobHandler`: 任务处理器接口
  - `@XxlJob`: Spring Bean 任务处理器注解
  - 支持 GLUE、脚本和方法任务处理器
- OpenAPI：`com.xxl.job.core.openapi` 包 - 通信协议

**通信协议**
- 管理中心与执行器之间基于 Netty 的 HTTP 通信
- 执行器内嵌 Netty 服务器（`EmbedServer`）接收管理中心请求
- 管理中心通过 HTTP 客户端连接执行器
- 默认执行器端口：9999（可配置）

### 关键架构模式

**任务路由策略** (`xxl-job-admin/scheduler/route/strategy/`)
- 第一个、最后一个、轮询、随机
- 一致性哈希、最不经常使用、最近最久未使用
- 故障转移、忙碌转移

**任务处理器类型**
- BEAN：带有 `@XxlJob` 注解的 Spring Bean
- GLUE：从管理中心加载的动态代码
- SCRIPT：Shell、Python、Node.js、PHP、PowerShell 脚本

**线程管理**
- 管理中心：快速/慢速触发线程池
- 执行器：JobThread 用于任务执行，回调线程
- 支持优雅停机，可配置等待时间

## 数据库架构

位于 `doc/db/tables_xxl_job.sql`

核心表：
- `xxl_job_group`: 执行器组和注册配置
- `xxl_job_info`: 任务定义和调度配置
- `xxl_job_log`: 执行日志
- `xxl_job_registry`: 执行器自动注册
- `xxl_job_logglue`: GLUE 代码版本历史

## 配置文件

**管理中心配置** (`xxl-job-admin/src/main/resources/application.properties`)
- 数据库连接：`spring.datasource.url`
- 访问令牌：`xxl.job.accessToken`
- 线程池大小：`xxl.job.triggerpool.fast.max`、`xxl.job.triggerpool.slow.max`
- 邮件告警设置：`spring.mail.*`

**执行器配置**
- Bean：`XxlJobExecutor`（Spring）或 `XxlJobSimpleExecutor`（无框架）
- 属性：adminAddresses、appname、port、accessToken、logPath

## 重要说明

- 目标 Java 版本：17（确保正确的 JDK 版本）
- Spring Boot 4.0.5 框架
- MyBatis 用于数据库访问
- Netty 4.2.12 用于网络通信
- 项目使用 GPL v3 许可证
- 默认管理中心端口：8080，上下文路径：/xxl-job-admin
- 默认执行器端口：9999
- 优雅停机等待任务完成时间为 5 秒

## 开发工作流程

**添加新的任务处理器时：**
1. 创建带有 `@XxlJob("handlerName")` 注解的处理方法
2. 实现 `IJobHandler` 接口或使用注解方式
3. 通过 `XxlJobExecutor` bean 注册处理器
4. 在管理中心 UI 中配置任务

**修改路由策略时：**
1. 在 `xxl-job-admin/scheduler/route/strategy/` 中继承 `ExecutorRouter`
2. 在 `ExecutorRouteStrategyEnum.java` 中添加策略枚举
3. 更新 UI 以包含新的策略选项

**调试通信问题时：**
- 检查管理中心和执行器的 accessToken 是否匹配
- 验证到执行器端口的网络连接
- 检查执行器注册表中的地址是否正确
- 检查执行器应用中的 Netty 服务器日志