# XXL-JOB 服务注册与任务触发机制详解

## 一、概述

XXL-JOB 采用 **HTTP + Netty** 的通信方式，结合 **主动注册** 和 **远程触发** 的机制来实现分布式任务调度。其他服务（执行器）通过主动注册到管理中心，管理中心通过 HTTP 远程调用执行器的 Netty 服务器来触发定时任务。

## 二、服务注册机制

### 2.1 注册方式：主动注册模式

执行器（Executor）采用 **主动注册** 的方式向管理中心注册自己的信息，而不是通过管理中心被动发现。

### 2.2 注册流程

#### **步骤一：执行器启动 Netty 服务器**
```java
// EmbedServer.start()
public void start(final String address, final int port, final String appname, final String accessToken) {
    // 创建 Netty 服务器
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel channel) throws Exception {
                    channel.pipeline()
                            .addLast(new IdleStateHandler(0, 0, 30 * 3, TimeUnit.SECONDS))  // 心跳检测
                            .addLast(new HttpServerCodec())
                            .addLast(new HttpObjectAggregator(5 * 1024 * 1024))  // HTTP 请求聚合
                            .addLast(new EmbedHttpServerHandler(executorBiz, accessToken, bizThreadPool));
                }
            });
    
    // 绑定端口
    ChannelFuture future = bootstrap.bind(port).sync();
    
    // 启动注册线程
    startRegistry(appname, address);
}
```

#### **步骤二：执行器启动注册线程**
```java
// EmbedServer.startRegistry()
private void startRegistry(String appname, String address) {
    // 启动注册线程，定期向管理中心注册
    ExecutorRegistryThread.getInstance().start(appname, address);
}
```

#### **步骤三：定期向管理中心注册**
```java
// ExecutorRegistryThread.start()
public void start(final String appname, final String address) {
    registryThread = new Thread(new Runnable() {
        @Override
        public void run() {
            while (!toStop) {
                try {
                    // 创建注册请求
                    RegistryRequest registryParam = new RegistryRequest(
                        RegistType.EXECUTOR.name(),  // 注册类型：EXECUTOR
                        appname,                      // 执行器名称
                        address                       // 执行器地址
                    );
                    
                    // 向所有管理中心注册
                    for (AdminBiz adminBiz: XxlJobExecutor.getAdminBizList()) {
                        try {
                            Response<String> registryResult = adminBiz.registry(registryParam);
                            if (registryResult.isSuccess()) {
                                logger.debug(">>>>>>>>>>> xxl-job registry success");
                                break;  // 任一管理中心注册成功即可
                            }
                        } catch (Throwable e) {
                            logger.info(">>>>>>>>>>> xxl-job registry error", e);
                        }
                    }
                } catch (Throwable e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                
                // 等待一定时间后再次注册（默认 30 秒）
                try {
                    if (!toStop) {
                        TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);  // 30秒
                    }
                } catch (Throwable e) {
                    logger.warn("registry thread interrupted", e);
                }
            }
        }
    });
    registryThread.setDaemon(true);
    registryThread.setName("xxl-job, executor ExecutorRegistryThread");
    registryThread.start();
}
```

### 2.3 管理中心处理注册请求

#### **接收注册请求**
```java
// AdminBizImpl.registry()
@Override
public Response<String> registry(RegistryRequest registryRequest) {
    return XxlJobAdminBootstrap.getInstance().getJobRegistryHelper().registry(registryRequest);
}
```

#### **异步处理注册**
```java
// JobRegistryHelper.registry()
public Response<String> registry(RegistryRequest registryParam) {
    // 参数验证
    if (StringTool.isBlank(registryParam.getRegistryGroup())
            || StringTool.isBlank(registryParam.getRegistryKey())
            || StringTool.isBlank(registryParam.getRegistryValue())) {
        return Response.ofFail("Illegal Argument.");
    }
    
    // 异步执行注册
    registryOrRemoveThreadPool.execute(new Runnable() {
        @Override
        public void run() {
            // 保存或更新注册信息
            int ret = XxlJobAdminBootstrap.getInstance().getXxlJobRegistryMapper()
                .registrySaveOrUpdate(
                    registryParam.getRegistryGroup(),  // EXECUTOR
                    registryParam.getRegistryKey(),     // appname
                    registryParam.getRegistryValue(),   // address
                    new Date()
                );
        }
    });
    
    return Response.ofSuccess();
}
```

#### **数据库存储**
```sql
-- 注册信息存储在 xxl_job_registry 表中
CREATE TABLE `xxl_job_registry` (
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `registry_group`    varchar(50)  NOT NULL,      -- EXECUTOR
    `registry_key`      varchar(255) NOT NULL,      -- appname
    `registry_value`    varchar(255) NOT NULL,      -- address
    `update_time`       datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_g_k_v` (`registry_group`, `registry_key`, `registry_value`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 2.4 注册信息监控与维护

#### **管理中心监控线程**
管理中心有一个监控线程定期处理注册信息：

```java
// JobRegistryHelper 监控线程
registryMonitorThread = new Thread(new Runnable() {
    @Override
    public void run() {
        while (!toStop) {
            try {
                // 1、移除过期的执行器（超过 90 秒未更新）
                List<Integer> ids = XxlJobAdminBootstrap.getInstance()
                    .getXxlJobRegistryMapper().findDead(Const.DEAD_TIMEOUT, new Date());
                if (ids!=null && !ids.isEmpty()) {
                    XxlJobAdminBootstrap.getInstance().getXxlJobRegistryMapper().removeDead(ids);
                }
                
                // 2、获取在线的执行器列表
                HashMap<String, List<String>> appAddressMap = new HashMap<>();
                List<XxlJobRegistry> list = XxlJobAdminBootstrap.getInstance()
                    .getXxlJobRegistryMapper().findAll(Const.DEAD_TIMEOUT, new Date());
                
                // 3、按应用名分组地址列表
                for (XxlJobRegistry item: list) {
                    if (RegistType.EXECUTOR.name().equals(item.getRegistryGroup())) {
                        String appname = item.getRegistryKey();
                        List<String> registryList = appAddressMap.get(appname);
                        if (registryList == null) {
                            registryList = new ArrayList<>();
                        }
                        if (!registryList.contains(item.getRegistryValue())) {
                            registryList.add(item.getRegistryValue());
                        }
                        appAddressMap.put(appname, registryList);
                    }
                }
                
                // 4、更新执行器组的地址列表
                for (XxlJobGroup group: groupList) {
                    List<String> registryList = appAddressMap.get(group.getAppname());
                    String addressListStr = null;
                    if (registryList!=null && !registryList.isEmpty()) {
                        Collections.sort(registryList);  // 排序
                        // 构建逗号分隔的地址列表
                        addressListStr = String.join(",", registryList);
                    }
                    group.setAddressList(addressListStr);
                    group.setUpdateTime(new Date());
                    XxlJobAdminBootstrap.getInstance().getXxlJobGroupMapper().update(group);
                }
            } catch (Throwable e) {
                logger.error("job registry monitor thread error", e);
            }
            
            // 每 30 秒执行一次
            TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);
        }
    }
});
```

## 三、任务触发机制

### 3.1 触发方式：HTTP 远程调用

管理中心通过 **HTTP 客户端** 远程调用执行器的 Netty 服务器来触发任务。

### 3.2 完整的触发流程

#### **步骤一：任务调度器触发任务**
```java
// JobScheduleHelper 中的任务调度
// 根据 Cron 表达式计算触发时间，然后调用 JobTrigger.trigger()
jobTrigger.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
```

#### **步骤二：创建触发请求**
```java
// JobTrigger.trigger()
public void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, 
                   String executorShardingParam, String executorParam, String addressList) {
    
    // 1、加载任务信息
    XxlJobInfo jobInfo = xxlJobInfoMapper.loadById(jobId);
    XxlJobGroup group = xxlJobGroupMapper.load(jobInfo.getJobGroup());
    
    // 2、保存触发日志
    XxlJobLog jobLog = new XxlJobLog();
    jobLog.setJobGroup(jobInfo.getJobGroup());
    jobLog.setJobId(jobInfo.getId());
    jobLog.setTriggerTime(new Date());
    xxlJobLogMapper.save(jobLog);
    
    // 3、构建触发请求
    TriggerRequest triggerParam = new TriggerRequest();
    triggerParam.setJobId(jobInfo.getId());
    triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
    triggerParam.setExecutorParams(jobInfo.getExecutorParam());
    triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
    triggerParam.setExecutorTimeout(jobInfo.getExecutorTimeout());
    triggerParam.setLogId(jobLog.getId());
    triggerParam.setLogDateTime(jobLog.getTriggerTime().getTime());
    triggerParam.setGlueType(jobInfo.getGlueType());
    triggerParam.setGlueSource(jobInfo.getGlueSource());
    triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
    triggerParam.setBroadcastIndex(index);
    triggerParam.setBroadcastTotal(total);
    
    // 4、选择执行器地址
    String address = null;
    if (group.getRegistryList()!=null && !group.getRegistryList().isEmpty()) {
        // 根据路由策略选择地址
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = 
            ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);
        
        Response<String> routeAddressResult = 
            executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList());
        
        if (routeAddressResult.isSuccess()) {
            address = routeAddressResult.getData();
        }
    }
    
    // 5、触发远程执行器
    Response<String> triggerResult = null;
    if (address != null) {
        triggerResult = doTrigger(triggerParam, address);
    } else {
        triggerResult = Response.of(XxlJobContext.HANDLE_CODE_FAIL, "Address Router Fail.");
    }
}
```

#### **步骤三：HTTP 远程调用执行器**
```java
// JobTrigger.doTrigger()
private Response<String> doTrigger(TriggerRequest triggerParam, String address) {
    try {
        // 1、构建 HTTP 客户端
        ExecutorBiz executorBiz = XxlJobAdminBootstrap.getExecutorBiz(address);
        
        // 2、调用执行器的 run 方法
        Response<String> runResult = executorBiz.run(triggerParam);
        
        // 3、构建结果
        StringBuffer runResultSB = new StringBuffer("trigger run：");
        runResultSB.append("<br>address：").append(address);
        runResultSB.append("<br>code：").append(runResult.getCode());
        runResultSB.append("<br>msg：").append(runResult.getMsg());
        
        runResult.setMsg(runResultSB.toString());
        return runResult;
    } catch (Exception e) {
        logger.error("xxl-job trigger error, please check if the executor[{}] is running.", address, e);
        return Response.of(XxlJobContext.HANDLE_CODE_FAIL, ThrowableTool.toString(e));
    }
}
```

#### **步骤四：获取执行器客户端**
```java
// XxlJobAdminBootstrap.getExecutorBiz()
public static ExecutorBiz getExecutorBiz(String address) throws Exception {
    // 1、参数验证
    if (StringTool.isBlank(address)) {
        return null;
    }
    
    // 2、从缓存中加载
    address = address.trim();
    ExecutorBiz executorBiz = executorBizRepository.get(address);
    if (executorBiz != null) {
        return executorBiz;
    }
    
    // 3、创建新的 HTTP 客户端
    executorBiz = HttpTool.createClient()
            .url(address)                                          // http://ip:port
            .timeout(XxlJobAdminBootstrap.getInstance().getTimeout() * 1000)
            .header(Const.XXL_JOB_ACCESS_TOKEN, XxlJobAdminBootstrap.getInstance().getAccessToken())
            .proxy(ExecutorBiz.class);  // 创建动态代理
    
    // 4、放入缓存
    executorBizRepository.put(address, executorBiz);
    return executorBiz;
}
```

### 3.3 执行器处理触发请求

#### **接收 HTTP 请求**
```java
// EmbedServer.EmbedHttpServerHandler.channelRead0()
@Override
protected void channelRead0(final ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
    // 1、解析请求
    String requestData = msg.content().toString(CharsetUtil.UTF_8);
    String uri = msg.uri();
    
    // 2、验证 accessToken
    if (accessToken != null && accessToken.trim().length() > 0 
            && !accessToken.trim().equals(msg.headers().get(Const.XXL_JOB_ACCESS_TOKEN))) {
        // 返回认证失败
        return;
    }
    
    // 3、处理业务逻辑
    try {
        // 根据_uri路径路由到不同的方法
        if ("/run".equals(uri)) {
            // 处理任务触发
            executorBiz.run(triggerRequest);
        } else if ("/beat".equals(uri)) {
            // 心跳检测
            executorBiz.beat();
        } else if ("/idleBeat".equals(uri)) {
            // 空闲检测
            executorBiz.idleBeat(idleBeatRequest);
        } else if ("/kill".equals(uri)) {
            // 终止任务
            executorBiz.kill(killRequest);
        } else if ("/log".equals(uri)) {
            // 日志查询
            executorBiz.log(logRequest);
        }
    } catch (Exception e) {
        // 返回错误响应
    }
}
```

#### **处理任务执行**
```java
// ExecutorBizImpl.run()
@Override
public Response<String> run(TriggerRequest triggerRequest) {
    // 1、加载已存在的 JobThread 和 JobHandler
    JobThread jobThread = XxlJobExecutor.loadJobThread(triggerRequest.getJobId());
    IJobHandler jobHandler = jobThread!=null?jobThread.getHandler():null;
    
    // 2、根据任务类型创建或验证 JobHandler
    GlueTypeEnum glueTypeEnum = GlueTypeEnum.match(triggerRequest.getGlueType());
    if (GlueTypeEnum.BEAN == glueTypeEnum) {
        // BEAN 模式：加载 Spring Bean 方法
        IJobHandler newJobHandler = XxlJobExecutor.loadJobHandler(triggerRequest.getExecutorHandler());
        
        if (jobThread!=null && jobHandler != newJobHandler) {
            // Handler 变化，需要停止旧线程
            jobThread = null;
            jobHandler = null;
        }
        
        if (jobHandler == null) {
            jobHandler = newJobHandler;
        }
    } else if (GlueTypeEnum.GLUE_GROOVY == glueTypeEnum) {
        // GLUE 模式：动态编译 Groovy 代码
        if (jobHandler == null) {
            IJobHandler originJobHandler = GlueFactory.getInstance().loadNewInstance(triggerRequest.getGlueSource());
            jobHandler = new GlueJobHandler(originJobHandler, triggerRequest.getGlueUpdatetime());
        }
    } else if (glueTypeEnum!=null && glueTypeEnum.isScript()) {
        // 脚本模式：创建脚本处理器
        if (jobHandler == null) {
            jobHandler = new ScriptJobHandler(triggerRequest.getJobId(), 
                                           triggerRequest.getGlueUpdatetime(), 
                                           triggerRequest.getGlueSource(), 
                                           glueTypeEnum);
        }
    }
    
    // 3、处理阻塞策略
    if (jobThread != null) {
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(
            triggerRequest.getExecutorBlockStrategy(), null);
        
        if (ExecutorBlockStrategyEnum.DISCARD_LATER == blockStrategy) {
            // 丢弃后续调度
            if (jobThread.isRunningOrHasQueue()) {
                return Response.of(XxlJobContext.HANDLE_CODE_FAIL, "block strategy effect：DISCARD_LATER");
            }
        } else if (ExecutorBlockStrategyEnum.COVER_EARLY == blockStrategy) {
            // 覆盖之前调度
            if (jobThread.isRunningOrHasQueue()) {
                jobThread = null;  // 停止旧线程
            }
        }
    }
    
    // 4、注册或创建 JobThread
    if (jobThread == null) {
        jobThread = XxlJobExecutor.registJobThread(triggerRequest.getJobId(), jobHandler, removeOldReason);
    }
    
    // 5、将触发请求推入队列
    return jobThread.pushTriggerQueue(triggerRequest);
}
```

## 四、通信协议详解

### 4.1 通信架构

```
┌─────────────────┐                    ┌─────────────────┐
│   管理中心        │                    │   执行器         │
│  (Admin Center) │                    │  (Executor)     │
└────────┬────────┘                    └────────┬────────┘
         │                                      │
         │ 1、执行器启动 Netty 服务器             │
         │ ←─────────────────────────────────  │
         │                                      │
         │ 2、执行器主动注册（每 30 秒）           │
         │ ←─────────────────────────────────  │
         │     HTTP POST /api/registry          │
         │                                      │
         │ 3、管理中心存储注册信息               │
         │     → 存储到 xxl_job_registry 表     │
         │                                      │
         │ 4、管理中心监控线程维护注册列表        │
         │     → 更新 xxl_job_group 表          │
         │                                      │
         │ 5、任务调度器触发任务                 │
         │ ─────────────────────────────────→  │
         │     HTTP POST /run                   │
         │                                      │
         │ 6、执行器处理任务                     │
         │ ←─────────────────────────────────  │
         │     返回执行结果                       │
         │                                      │
         │ 7、执行器回调执行结果                 │
         │ ←─────────────────────────────────  │
         │     HTTP POST /api/callback           │
```

### 4.2 关键通信接口

#### **注册接口**
```java
// 执行器 → 管理中心
POST /api/registry
Content-Type: application/json

{
    "registryGroup": "EXECUTOR",
    "registryKey": "demo-app",
    "registryValue": "http://192.168.1.100:9999"
}

Response:
{
    "code": 200,
    "msg": null,
    "content": null
}
```

#### **任务触发接口**
```java
// 管理中心 → 执行器
POST /run
Content-Type: application/json
XXL-JOB-ACCESS-TOKEN: default_token

{
    "jobId": 1,
    "executorHandler": "demoJobHandler",
    "executorParams": "job-param",
    "executorBlockStrategy": "SERIAL_EXECUTION",
    "executorTimeout": 0,
    "logId": 12345,
    "logDateTime": 1710123456789,
    "glueType": "BEAN",
    "glueSource": "",
    "glueUpdatetime": 1710123456789,
    "broadcastIndex": 0,
    "broadcastTotal": 1
}

Response:
{
    "code": 200,
    "msg": "",
    "content": null
}
```

#### **回调接口**
```java
// 执行器 → 管理中心
POST /api/callback
Content-Type: application/json
XXL-JOB-ACCESS-TOKEN: default_token

[
    {
        "logId": 12345,
        "logDateTim": 1710123456789,
        "handleCode": 200,
        "handleMsg": "执行成功"
    }
]

Response:
{
    "code": 200,
    "msg": null,
    "content": null
}
```

## 五、核心特点总结

### 5.1 注册机制特点

1. **主动注册**：执行器主动向管理中心注册，而非管理中心被动发现
2. **心跳保持**：每 30 秒注册一次，保持在线状态
3. **自动清理**：管理中心定期清理超过 90 秒未更新的执行器
4. **多中心支持**：执行器可向多个管理中心注册，提高可用性

### 5.2 触发机制特点

1. **HTTP 协议**：基于标准 HTTP 协议，跨语言、跨平台
2. **Netty 服务器**：执行器内嵌 Netty 服务器，高性能处理请求
3. **异步调用**：管理中心异步触发任务，不阻塞调度流程
4. **连接复用**：管理中心缓存执行器连接，减少连接开销

### 5.3 容错机制

1. **故障转移**：执行器故障时自动切换到其他健康节点
2. **阻塞策略**：支持单机串行、丢弃后续、覆盖之前等策略
3. **失败重试**：支持配置失败重试次数
4. **回调重试**：执行结果回调失败时自动重试

### 5.4 优势分析

1. **简单可靠**：基于 HTTP 协议，易于理解和调试
2. **高性能**：Netty 异步处理，支持高并发
3. **可扩展**：支持水平扩展，增加执行器节点
4. **跨语言**：HTTP 协议支持多语言接入
5. **容错性强**：多层容错机制，保证系统稳定性

## 六、与其它方案的对比

### 6.1 对比传统 RPC 框架

| 特性 | XXL-JOB | Dubbo | gRPC |
|------|---------|-------|-------|
| **协议** | HTTP | TCP/HTTP2 | HTTP2 |
| **序列化** | JSON | Hessian | Protobuf |
| **复杂度** | 简单 | 中等 | 复杂 |
| **跨语言** | 好 | 一般 | 好 |
| **性能** | 中等 | 高 | 高 |
| **调试便利性** | 好 | 一般 | 一般 |

### 6.2 对比消息队列方案

| 特性 | XXL-JOB | 消息队列 |
|------|---------|----------|
| **实时性** | 高 | 低（有延迟） |
| **可靠性** | 高 | 高 |
| **复杂度** | 低 | 高 |
| **定时精度** | 高（Cron） | 低 |
| **状态管理** | 集中式 | 分布式 |

## 七、最佳实践建议

### 7.1 执行器配置
1. **合理设置端口**：避免端口冲突，使用 9999 默认端口
2. **设置心跳间隔**：默认 30 秒，可根据网络情况调整
3. **配置访问令牌**：提高系统安全性
4. **多中心部署**：生产环境建议部署多个管理中心

### 7.2 网络配置
1. **防火墙规则**：开放执行器端口，允许管理中心访问
2. **负载均衡**：执行器集群部署，提高可用性
3. **监控告警**：监控注册状态和触发成功率

### 7.3 性能优化
1. **连接池配置**：合理设置 HTTP 客户端连接池大小
2. **线程池优化**：根据业务特点调整线程池参数
3. **日志管理**：合理设置日志保留天数，避免磁盘占满

XXL-JOB 的这种基于 HTTP + Netty 的注册和触发机制，在保证简单易用的同时，也提供了足够的性能和可靠性，是目前分布式任务调度领域的优秀实践。