# XXL-JOB 执行器注册机制详解

## 一、注册机制概述

XXL-JOB 的执行器注册采用 **HTTP + 心跳** 的机制，执行器定期通过 HTTP 请求向管理端注册自己的存在，管理端维护执行器的在线状态。

### 核心特点

- **跨服务器通信**: 基于 HTTP 协议的远程调用
- **心跳机制**: 执行器每 30 秒向管理端发送心跳
- **自动清理**: 管理端自动清理 90 秒未更新的死亡节点
- **异步处理**: 注册请求异步处理，避免阻塞主线程
- **多管理端支持**: 支持同时注册到多个管理端

## 二、注册流程架构

### 2.1 整体架构

```
执行器（Executor）                          管理端（Admin）
    │                                           │
    │ 1. 启动注册线程                           │
    │──────────────────────────────────────────→│
    │                                           │
    │ 2. 发送注册请求                           │
    │──────────────────────────────────────────→│ 3. 接收 API 请求
    │    POST /api/registry                     │    OpenApiController
    │    {                                      │    │
    │      "registryGroup": "EXECUTOR",        │    ↓
    │      "registryKey": "xxl-job-executor",  │    4. 调用 AdminBiz
    │      "registryValue": "http://1.1.1.1:9999"│    │
    │    }                                     │    ↓
    │                                           │    5. 异步处理注册
    │                                           │    JobRegistryHelper
    │                                           │    │
    │                                           │    ↓
    │                                           │    6. 数据库操作
    │                                           │    INSERT/UPDATE
    │                                           │    xxl_job_registry 表
    │                                           │
    │ ←─────────────────────────────────────────│ 7. 返回成功响应
    │    Response<String> success              │
    │                                           │
    │ 8. 等待 30 秒                            │
    │    sleep(30)                              │
    │                                           │
    │ 9. 重复步骤 2-8（心跳循环）                │
    │──────────────────────────────────────────→│
```

### 2.2 组件关系图

```
┌─────────────────┐         HTTP           ┌──────────────────┐
│  ExecutorRegistryThread  │───────────────────────────→│  OpenApiController   │
│  (执行器注册线程)    │                           │  (管理端 API 入口)  │
└─────────────────┘                           └──────────────────┘
         │                                               │
         │                                               │
         │                                               ↓
         │                                        ┌──────────────────┐
         │                                        │   AdminBizImpl    │
         │                                        │ (管理端业务实现)   │
         │                                        └──────────────────┘
         │                                               │
         │                                               ↓
         │                                        ┌──────────────────┐
         │                                        │ JobRegistryHelper │
         │                                        │ (注册助手类)      │
         │                                        └──────────────────┘
         │                                               │
         │                                               ↓
         │                                        ┌──────────────────┐
         │                                        │XxlJobRegistryMapper│
         │                                        │ (数据库操作)      │
         │                                        └──────────────────┘
         │                                               │
         ↓                                               ↓
┌─────────────────┐                           ┌──────────────────┐
│  AdminBiz (HTTP Client)              │   xxl_job_registry │
│  (管理端客户端)      │                           │   (数据库表)       │
└─────────────────┘                           └──────────────────┘
```

## 三、执行器端实现

### 3.1 ExecutorRegistryThread（注册线程）

**作用**: 执行器启动后创建的专用线程，负责向管理端注册和心跳维护。

#### 启动流程

```java
public void start(final String appname, final String address) {
    // 1、参数验证
    if (appname == null || appname.trim().length() == 0) {
        logger.warn("执行器注册配置失败，appname 为空");
        return;
    }
    
    // 2、检查管理端配置
    if (XxlJobExecutor.getAdminBizList() == null) {
        logger.warn("执行器注册配置失败，管理端地址为空");
        return;
    }
    
    // 3、创建注册线程
    registryThread = new Thread(new Runnable() {
        @Override
        public void run() {
            // 注册循环
            while (!toStop) {
                // 4、发送注册请求
                registryOrRemove();
                
                // 5、等待心跳间隔（30秒）
                TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);
            }
            
            // 6、停止时移除注册
            registryRemoveOnStop();
        }
    });
    
    // 7、启动线程
    registryThread.setDaemon(true);
    registryThread.setName("xxl-job, executor ExecutorRegistryThread");
    registryThread.start();
}
```

#### 注册请求构造

```java
// 构造注册参数
RegistryRequest registryParam = new RegistryRequest(
    RegistType.EXECUTOR.name(),  // 注册类型：EXECUTOR
    appname,                      // 执行器应用名
    address                       // 执行器地址：http://ip:port
);

// 向所有管理端发送注册请求
for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
    try {
        Response<String> registryResult = adminBiz.registry(registryParam);
        if (registryResult != null && registryResult.isSuccess()) {
            logger.debug("注册成功，参数：{}，结果：{}", registryParam, registryResult);
            break;  // 任一管理端注册成功则跳出
        }
    } catch (Throwable e) {
        logger.info("注册失败，参数：{}", registryParam, e);
    }
}
```

### 3.2 AdminBiz 客户端创建

**关键**: 执行器通过 HTTP 客户端代理创建管理端的调用接口。

```java
// 在 XxlJobExecutor 中创建管理端客户端
private void initAdminBizList(String adminAddresses, String accessToken, int timeout) {
    // 1、解析管理端地址列表
    for (String address : adminAddresses.trim().split(",")) {
        String finalAddress = address.trim();
        finalAddress = finalAddress.endsWith("/") 
            ? (finalAddress + "api") 
            : (finalAddress + "/api");
        
        // 2、创建 HTTP 客户端代理
        AdminBiz adminBiz = HttpTool.createClient()
            .url(finalAddress)                          // 管理端地址
            .timeout(finalTimeout * 1000)               // 超时时间
            .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken)  // 访问令牌
            .proxy(AdminBiz.class);                     // 创建代理接口
        
        // 3、添加到管理端列表
        if (adminBizList == null) {
            adminBizList = new ArrayList<AdminBiz>();
        }
        adminBizList.add(adminBiz);
    }
}
```

**HTTP 调用示例**：
```
POST http://127.0.0.1:8080/xxl-job-admin/api/registry
Headers:
  XXL-JOB-ACCESS-TOKEN: default_token
Body:
  {
    "registryGroup": "EXECUTOR",
    "registryKey": "xxl-job-executor-sample",
    "registryValue": "http://192.168.1.100:9999"
  }
```

## 四、管理端实现

### 4.1 OpenApiController（API 入口）

**作用**: 接收执行器的 HTTP 请求，进行权限验证和请求分发。

```java
@Controller
public class OpenApiController {
    
    @Resource
    private AdminBiz adminBiz;
    
    /**
     * 统一 API 入口
     */
    @RequestMapping("/api/{uri}")
    @ResponseBody
    @XxlSso(login = false)  // 跳过 SSO 登录验证
    public Object api(HttpServletRequest request,
                      @PathVariable("uri") String uri,
                      @RequestHeader(value = Const.XXL_JOB_ACCESS_TOKEN, required = false) String accessToken,
                      @RequestBody(required = false) String requestBody) {
        
        // 1、验证请求方法
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return Response.ofFail("不支持的请求方法");
        }
        
        // 2、验证请求体
        if (StringTool.isBlank(requestBody)) {
            return Response.ofFail("请求体为空");
        }
        
        // 3、验证访问令牌
        if (StringTool.isNotBlank(XxlJobAdminBootstrap.getInstance().getAccessToken())
                && !XxlJobAdminBootstrap.getInstance().getAccessToken().equals(accessToken)) {
            return Response.ofFail("访问令牌错误");
        }
        
        // 4、分发请求
        switch (uri) {
            case "registry": {
                RegistryRequest registryParam = GsonTool.fromJson(requestBody, RegistryRequest.class);
                return adminBiz.registry(registryParam);
            }
            case "registryRemove": {
                RegistryRequest registryParam = GsonTool.fromJson(requestBody, RegistryRequest.class);
                return adminBiz.registryRemove(registryParam);
            }
            case "callback": {
                List<CallbackRequest> callbackParamList = GsonTool.fromJson(requestBody, List.class, CallbackRequest.class);
                return adminBiz.callback(callbackParamList);
            }
            default:
                return Response.ofFail("无效的请求路径: " + uri);
        }
    }
}
```

### 4.2 AdminBizImpl（业务实现）

**作用**: 实现具体的注册业务逻辑。

```java
@Service
public class AdminBizImpl implements AdminBiz {
    
    @Override
    public Response<String> registry(RegistryRequest registryRequest) {
        return XxlJobAdminBootstrap.getInstance()
            .getJobRegistryHelper()
            .registry(registryRequest);
    }
    
    @Override
    public Response<String> registryRemove(RegistryRequest registryRequest) {
        return XxlJobAdminBootstrap.getInstance()
            .getJobRegistryHelper()
            .registryRemove(registryRequest);
    }
}
```

### 4.3 JobRegistryHelper（注册助手）

**作用**: 处理注册请求的异步执行和数据库操作。

#### 注册处理

```java
public Response<String> registry(RegistryRequest registryParam) {
    // 1、参数验证
    if (StringTool.isBlank(registryParam.getRegistryGroup())
            || StringTool.isBlank(registryParam.getRegistryKey())
            || StringTool.isBlank(registryParam.getRegistryValue())) {
        return Response.ofFail("参数不合法");
    }
    
    // 2、异步执行数据库操作（避免阻塞 HTTP 请求）
    registryOrRemoveThreadPool.execute(new Runnable() {
        @Override
        public void run() {
            // 3、保存或更新注册信息
            int ret = XxlJobAdminBootstrap.getInstance()
                .getXxlJobRegistryMapper()
                .registrySaveOrUpdate(
                    registryParam.getRegistryGroup(),
                    registryParam.getRegistryKey(),
                    registryParam.getRegistryValue(),
                    new Date()
                );
            
            // 4、如果是新注册，刷新执行器组信息
            if (ret == 1) {
                freshGroupRegistryInfo(registryParam);
            }
        }
    });
    
    // 5、立即返回成功（异步处理）
    return Response.ofSuccess();
}
```

#### 移除注册处理

```java
public Response<String> registryRemove(RegistryRequest registryParam) {
    // 1、参数验证
    if (StringTool.isBlank(registryParam.getRegistryGroup())
            || StringTool.isBlank(registryParam.getRegistryKey())
            || StringTool.isBlank(registryParam.getRegistryValue())) {
        return Response.ofFail("参数不合法");
    }
    
    // 2、异步执行删除操作
    registryOrRemoveThreadPool.execute(new Runnable() {
        @Override
        public void run() {
            // 3、删除注册记录
            int ret = XxlJobAdminBootstrap.getInstance()
                .getXxlJobRegistryMapper()
                .registryDelete(
                    registryParam.getRegistryGroup(),
                    registryParam.getRegistryKey(),
                    registryParam.getRegistryValue()
                );
            
            // 4、刷新执行器组信息
            if (ret > 0) {
                freshGroupRegistryInfo(registryParam);
            }
        }
    });
    
    // 5、立即返回成功
    return Response.ofSuccess();
}
```

### 4.4 数据库操作

**SQL 语句**: 使用 MySQL 的 `ON DUPLICATE KEY UPDATE` 语法实现插入或更新。

```sql
INSERT INTO xxl_job_registry(
    `registry_group`,
    `registry_key`, 
    `registry_value`,
    `update_time`
)
VALUES(
    #{registryGroup},
    #{registryKey},
    #{registryValue},
    #{updateTime}
)
ON DUPLICATE KEY UPDATE
    `update_time` = #{updateTime}
```

**删除 SQL**:
```sql
DELETE FROM xxl_job_registry
WHERE registry_group = #{registryGroup}
  AND registry_key = #{registryKey}
  AND registry_value = #{registryValue}
```

## 五、心跳与死亡检测机制

### 5.1 心跳机制

**执行器端**:
```java
// 注册线程中，每 30 秒发送一次心跳
while (!toStop) {
    // 发送注册请求（心跳）
    registryOrRemove();
    
    // 等待 30 秒
    TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);  // 30 秒
}
```

**管理端**:
```java
// 每次注册都会更新 update_time
INSERT INTO xxl_job_registry(...) VALUES(...) 
ON DUPLICATE KEY UPDATE `update_time` = NOW()
```

### 5.2 死亡检测线程

**JobRegistryHelper 中启动监控线程**:

```java
public void start() {
    registryMonitorThread = new Thread(new Runnable() {
        @Override
        public void run() {
            while (!toStop) {
                try {
                    // 1、查询自动注册的执行器组
                    List<XxlJobGroup> groupList = XxlJobAdminBootstrap.getInstance()
                        .getXxlJobGroupMapper()
                        .findByAddressType(0);
                    
                    if (groupList != null && !groupList.isEmpty()) {
                        
                        // 2、移除死亡节点（90秒未更新）
                        List<Integer> deadIds = XxlJobAdminBootstrap.getInstance()
                            .getXxlJobRegistryMapper()
                            .findDead(Const.DEAD_TIMEOUT, new Date());
                        
                        if (deadIds != null && !deadIds.isEmpty()) {
                            XxlJobAdminBootstrap.getInstance()
                                .getXxlJobRegistryMapper()
                                .removeDead(deadIds);
                        }
                        
                        // 3、获取所有在线节点
                        List<XxlJobRegistry> onlineList = XxlJobAdminBootstrap.getInstance()
                            .getXxlJobRegistryMapper()
                            .findAll(Const.DEAD_TIMEOUT, new Date());
                        
                        // 4、按应用名分组地址列表
                        HashMap<String, List<String>> appAddressMap = new HashMap<>();
                        for (XxlJobRegistry item : onlineList) {
                            if (RegistType.EXECUTOR.name().equals(item.getRegistryGroup())) {
                                String appname = item.getRegistryKey();
                                String address = item.getRegistryValue();
                                
                                List<String> addressList = appAddressMap.get(appname);
                                if (addressList == null) {
                                    addressList = new ArrayList<>();
                                }
                                
                                if (!addressList.contains(address)) {
                                    addressList.add(address);
                                }
                                appAddressMap.put(appname, addressList);
                            }
                        }
                        
                        // 5、更新执行器组的地址列表
                        for (XxlJobGroup group : groupList) {
                            List<String> addressList = appAddressMap.get(group.getAppname());
                            String addressListStr = null;
                            
                            if (addressList != null && !addressList.isEmpty()) {
                                Collections.sort(addressList);  // 排序
                                addressListStr = String.join(",", addressList);
                            }
                            
                            group.setAddressList(addressListStr);
                            group.setUpdateTime(new Date());
                            
                            XxlJobAdminBootstrap.getInstance()
                                .getXxlJobGroupMapper()
                                .update(group);
                        }
                    }
                    
                } catch (Throwable e) {
                    if (!toStop) {
                        logger.error("注册监控线程错误：{}", e);
                    }
                }
                
                // 每 30 秒检查一次
                TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);
            }
        }
    });
    
    registryMonitorThread.setDaemon(true);
    registryMonitorThread.setName("xxl-job, admin JobRegistryHelper-monitorThread");
    registryMonitorThread.start();
}
```

### 5.3 时间常量定义

```java
public class Const {
    // 心跳超时时间：30 秒
    public static final int BEAT_TIMEOUT = 30;
    
    // 死亡超时时间：90 秒（心跳超时的 3 倍）
    public static final int DEAD_TIMEOUT = BEAT_TIMEOUT * 3;
}
```

**死亡检测 SQL**:
```sql
-- 查找超过 90 秒未更新的节点
SELECT t.id
FROM xxl_job_registry AS t
WHERE t.update_time < DATE_ADD(#{nowTime}, INTERVAL -#{timeout} SECOND)

-- 查找 90 秒内有更新的在线节点
SELECT <include refid="Base_Column_List" />
FROM xxl_job_registry AS t
WHERE t.update_time > DATE_ADD(#{nowTime}, INTERVAL -#{timeout} SECOND)
```

## 六、跨服务器注册流程

### 6.1 完整的跨服务器调用链

```
┌─────────────────┐                      ┌─────────────────┐
│  执行器 A        │                      │  管理端 B        │
│  192.168.1.100  │                      │  192.168.1.200  │
└─────────────────┘                      └─────────────────┘
         │                                          │
         │ 1. 执行器启动                              │
         │    ExecutorRegistryThread.start()         │
         │                                          │
         │ 2. 创建 HTTP 客户端                         │
         │    AdminBiz adminBiz = HttpTool.createClient()│
         │        .url("http://192.168.1.200:8080/xxl-job-admin/api")│
         │        .proxy(AdminBiz.class);             │
         │                                          │
         │ 3. 构造注册请求                             │
         │    RegistryRequest(                       │
         │        "EXECUTOR",                         │
         │        "xxl-job-executor-sample",         │
         │        "http://192.168.1.100:9999"         │
         │    )                                     │
         │                                          │
         │ 4. 发送 HTTP POST 请求                      │
         │    POST http://192.168.1.200:8080/xxl-job-admin/api/registry│
         │    Header: XXL-JOB-ACCESS-TOKEN: default_token│
         │    Body: {"registryGroup":"EXECUTOR",...} │
         │─────────────────────────────────────────→│
         │                                          │
         │                                          │ 5. OpenApiController 接收请求
         │                                          │    @RequestMapping("/api/registry")
         │                                          │
         │                                          │ 6. 验证访问令牌
         │                                          │    if (accessToken == expectedToken)
         │                                          │
         │                                          │ 7. 调用 AdminBizImpl.registry()
         │                                          │
         │                                          │ 8. 异步执行数据库操作
         │                                          │    INSERT INTO xxl_job_registry
         │                                          │    VALUES(...) 
         │                                          │    ON DUPLICATE KEY UPDATE
         │                                          │
         │ 9. 接收响应                                │←──────────────────────────────────│
         │    Response<String> success              │
         │                                          │
         │ 10. 等待 30 秒                             │
         │     TimeUnit.SECONDS.sleep(30)            │
         │                                          │
         │ 11. 重复步骤 3-10（心跳循环）                 │
         │─────────────────────────────────────────→│
```

### 6.2 多管理端注册

**配置多个管理端地址**:
```properties
# 执行器配置
xxl.job.admin.addresses=http://192.168.1.200:8080/xxl-job-admin,http://192.168.1.201:8080/xxl-job-admin
```

**注册策略**:
```java
// 向所有管理端发送注册请求
for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
    try {
        Response<String> registryResult = adminBiz.registry(registryParam);
        if (registryResult != null && registryResult.isSuccess()) {
            logger.debug("注册成功");
            break;  // 任一管理端注册成功则跳出
        } else {
            logger.info("注册失败，尝试下一个管理端");
        }
    } catch (Throwable e) {
        logger.info("注册异常，尝试下一个管理端", e);
    }
}
```

## 七、数据库表设计

### 7.1 xxl_job_registry 表结构

```sql
CREATE TABLE `xxl_job_registry` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `registry_group` varchar(50) NOT NULL COMMENT '注册组：EXECUTOR 或 ADMIN',
  `registry_key` varchar(255) NOT NULL COMMENT '注册键：执行器 appname 或管理端 key',
  `registry_value` varchar(255) NOT NULL COMMENT '注册值：执行器地址或管理端地址',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间，用于心跳检测',
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_unique_key` (`registry_group`,`registry_key`,`registry_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**字段说明**:
- `registry_group`: 注册组类型
  - `EXECUTOR`: 执行器注册
  - `ADMIN`: 管理端注册
- `registry_key`: 注册键
  - 执行器：appname（如 "xxl-job-executor-sample"）
  - 管理端：固定值 "ADMIN"
- `registry_value`: 注册值
  - 执行器：`http://ip:port`（如 "http://192.168.1.100:9999"）
  - 管理端：`http://ip:port/context`
- `update_time`: 最后更新时间，用于心跳检测

### 7.2 数据示例

```sql
-- 执行器注册记录
INSERT INTO xxl_job_registry VALUES 
(1, 'EXECUTOR', 'xxl-job-executor-sample', 'http://192.168.1.100:9999', '2026-06-17 10:30:00'),
(2, 'EXECUTOR', 'xxl-job-executor-sample', 'http://192.168.1.101:9999', '2026-06-17 10:30:15'),
(3, 'EXECUTOR', 'order-executor', 'http://192.168.1.102:9999', '2026-06-17 10:29:50');

-- 管理端注册记录
INSERT INTO xxl_job_registry VALUES 
(4, 'ADMIN', 'ADMIN', 'http://192.168.1.200:8080/xxl-job-admin', '2026-06-17 10:30:00');
```

## 八、RegistryRequest 模型

### 8.1 数据结构

```java
public class RegistryRequest implements Serializable {
    private static final long serialVersionUID = 42L;
    
    // 注册组：EXECUTOR 或 ADMIN
    private String registryGroup;
    
    // 注册键：执行器 appname 或管理端 key
    private String registryKey;
    
    // 注册值：执行器地址或管理端地址
    private String registryValue;
    
    public RegistryRequest() {}
    
    public RegistryRequest(String registryGroup, String registryKey, String registryValue) {
        this.registryGroup = registryGroup;
        this.registryKey = registryKey;
        this.registryValue = registryValue;
    }
    
    // getters and setters...
}
```

### 8.2 使用示例

**执行器注册**:
```java
RegistryRequest request = new RegistryRequest(
    "EXECUTOR",                          // 注册组
    "xxl-job-executor-sample",         // 执行器 appname
    "http://192.168.1.100:9999"         // 执行器地址
);
```

**管理端注册**:
```java
RegistryRequest request = new RegistryRequest(
    "ADMIN",                             // 注册组
    "ADMIN",                             // 固定值
    "http://192.168.1.200:8080/xxl-job-admin"  // 管理端地址
);
```

## 九、故障处理与容错

### 9.1 网络故障处理

```java
// 执行器端：自动重试其他管理端
for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
    try {
        Response<String> registryResult = adminBiz.registry(registryParam);
        if (registryResult != null && registryResult.isSuccess()) {
            break;  // 成功则跳出
        }
    } catch (Throwable e) {
        logger.warn("注册失败，将尝试下一个管理端", e);
        // 继续尝试下一个管理端
    }
}
```

### 9.2 异步处理容错

```java
// 管理端：使用线程池异步处理，避免阻塞
registryOrRemoveThreadPool.execute(new Runnable() {
    @Override
    public void run() {
        try {
            // 数据库操作
            int ret = xxlJobRegistryMapper.registrySaveOrUpdate(...);
        } catch (Exception e) {
            logger.error("注册信息保存失败", e);
            // 不影响返回结果，下次心跳会重试
        }
    }
});

// 立即返回成功，提高响应速度
return Response.ofSuccess();
```

### 9.3 死亡节点自动清理

```java
// 定期清理超过 90 秒未更新的节点
List<Integer> deadIds = xxlJobRegistryMapper.findDead(Const.DEAD_TIMEOUT, new Date());
if (deadIds != null && !deadIds.isEmpty()) {
    xxlJobRegistryMapper.removeDead(deadIds);
    logger.info("清理死亡节点，数量：{}", deadIds.size());
}
```

## 十、总结

XXL-JOB 的执行器注册机制采用以下设计保证了分布式环境下的可靠性：

### 10.1 核心特性

1. **HTTP 远程调用**: 通过 HTTP 协议实现跨服务器通信
2. **心跳机制**: 每 30 秒发送一次心跳，维持在线状态
3. **异步处理**: 注册请求异步处理，提高响应速度
4. **自动容错**: 支持多管理端配置，自动故障转移
5. **死亡检测**: 自动清理 90 秒未更新的死亡节点

### 10.2 关键设计

1. **执行器端**: 
   - 专用注册线程 `ExecutorRegistryThread`
   - HTTP 客户端代理 `AdminBiz`
   - 定时心跳机制

2. **管理端**: 
   - 统一 API 入口 `OpenApiController`
   - 异步处理线程池
   - 自动监控和清理机制

3. **数据存储**: 
   - 专门的注册表 `xxl_job_registry`
   - 唯一约束防止重复注册
   - 基于 `update_time` 的心跳检测

这种设计使得 XXL-JOB 可以在复杂的网络环境下稳定运行，自动处理网络故障和节点故障，保证了分布式任务调度的可靠性。