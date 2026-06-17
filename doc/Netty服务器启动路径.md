# XXL-JOB 执行器启动 Netty 服务器完整路径梳理

## 一、完整调用链路概览

```
┌─────────────────────────────────────────────────────────────┐
│                    应用启动入口                              │
└────────────────────┬────────────────────────────────────────┘
                     │
        ┌────────────┴────────────┐
        │                         │
┌───────▼────────┐      ┌────────▼──────────┐
│ Spring Boot    │      │   Frameless       │
│ Application    │      │   Application     │
└───────┬────────┘      └────────┬──────────┘
        │                         │
┌───────▼────────┐      ┌────────▼──────────┐
│ Spring Bean    │      │   Main Method      │
│ Configuration  │      │   Direct Config    │
└───────┬────────┘      └────────┬──────────┘
        │                         │
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │  XxlJobSpringExecutor  │
        │  XxlJobSimpleExecutor  │
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │   XxlJobExecutor.start()│
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │   initEmbedServer()     │
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │   EmbedServer.start()   │
        └────────────┬────────────┘
                     │
        ┌────────────▼────────────┐
        │   Netty Server Start    │
        │   + Registry Thread     │
        └─────────────────────────┘
```

## 二、详细调用路径和代码位置

### 2.1 Spring Boot 应用入口

#### **路径一：Spring Boot 版本**
```java
// 文件位置：xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/java/com/xxl/job/executor/XxlJobExecutorApplication.java
package com.xxl.job.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class XxlJobExecutorApplication {
    public static void main(String[] args) {
        SpringApplication.run(XxlJobExecutorApplication.class, args);
    }
}
```

#### **路径二：Frameless 版本**
```java
// 文件位置：xxl-job-executor-samples/xxl-job-executor-sample-frameless/src/main/java/com/xxl/job/executor/sample/frameless/XxlJobFramelessApplication.java
package com.xxl.job.executor.sample.frameless;

import com.xxl.job.core.executor.impl.XxlJobSimpleExecutor;

public class XxlJobFramelessApplication {
    public static void main(String[] args) {
        // 直接创建和启动执行器
        XxlJobSimpleExecutor xxlJobSimpleExecutor = new XxlJobSimpleExecutor();
        xxlJobSimpleExecutor.setAdminAddresses("http://127.0.0.1:8080/xxl-job-admin");
        xxlJobSimpleExecutor.setAppname("xxl-job-executor-sample-frameless");
        xxlJobSimpleExecutor.setPort(9999);
        xxlJobSimpleExecutor.start();
    }
}
```

### 2.2 Spring Bean 配置阶段

#### **Spring Boot 配置类**
```java
// 文件位置：xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/java/com/xxl/job/executor/config/XxlJobConfig.java
@Configuration
public class XxlJobConfig {
    
    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;
    
    @Value("${xxl.job.admin.accessToken}")
    private String accessToken;
    
    @Value("${xxl.job.executor.appname}")
    private String appname;
    
    @Value("${xxl.job.executor.port}")
    private int port;
    
    // ... 其他配置
    
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        logger.info(">>>>>>>>>>> xxl-job config init.");
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        
        // 设置配置参数
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        xxlJobSpringExecutor.setAccessToken(accessToken);
        xxlJobSpringExecutor.setAppname(appname);
        xxlJobSpringExecutor.setPort(port);
        // ... 其他设置
        
        return xxlJobSpringExecutor;
    }
}
```

**配置文件（application.properties）**：
```properties
# xxl-job admin config
xxl.job.admin.addresses=http://127.0.0.1:8080/xxl-job-admin
xxl.job.admin.accessToken=default_token
xxl.job.admin.timeout=3

# xxl-job executor config
xxl.job.executor.enabled=true
xxl.job.executor.appname=xxl-job-executor-sample
xxl.job.executor.address=
xxl.job.executor.ip=
xxl.job.executor.port=9999
xxl.job.executor.logpath=/data/applogs/xxl-job/jobhandler
xxl.job.executor.logretentiondays=30
xxl.job.executor.excludedpackage=org.springframework.,spring.
```

### 2.3 Spring 生命周期回调

#### **XxlJobSpringExecutor 启动**
```java
// 文件位置：xxl-job-core/src/main/java/com/xxl/job/core/executor/impl/XxlJobSpringExecutor.java
public class XxlJobSpringExecutor extends XxlJobExecutor 
    implements ApplicationContextAware, SmartInitializingSingleton, DisposableBean {
    
    /**
     * Spring Bean 初始化完成后的回调
     */
    @Override
    public void afterSingletonsInstantiated() {
        
        // 1、扫描 @XxlJob 注解的方法
        scanJobHandlerMethod(applicationContext);
        
        // 2、刷新 GlueFactory 为 Spring 版本
        GlueFactory.refreshInstance(1);
        
        // 3、调用父类的 start 方法
        try {
            super.start();  // ← 关键调用点
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Spring 容器销毁时的回调
     */
    @Override
    public void destroy() {
        super.destroy();
    }
    
    /**
     * 扫描 @XxlJob 注解的方法并注册
     */
    private void scanJobHandlerMethod(ApplicationContext applicationContext) {
        // 扫描所有 Spring Bean
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class, false, false);
        
        for (String beanName : beanNames) {
            // 跳过排除的包
            if (isExcluded(excludedPackageList, beanClassName)) {
                continue;
            }
            
            // 查找带 @XxlJob 注解的方法
            Map<Method, XxlJob> annotatedMethods = MethodIntrospector.selectMethods(beanClass,
                new MethodIntrospector.MetadataLookup<XxlJob>() {
                    @Override
                    public XxlJob inspect(Method method) {
                        return AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
                    }
                });
            
            // 注册任务处理器
            if (annotatedMethods != null && !annotatedMethods.isEmpty()) {
                Object jobBean = applicationContext.getBean(beanName);
                for (Map.Entry<Method, XxlJob> jobMethodEntry : annotatedMethods.entrySet()) {
                    Method jobMethod = jobMethodEntry.getKey();
                    XxlJob xxlJob = jobMethodEntry.getValue();
                    registryJobHandler(xxlJob, jobBean, jobMethod);
                }
            }
        }
    }
}
```

### 2.4 执行器核心启动逻辑

#### **XxlJobExecutor.start() 方法**
```java
// 文件位置：xxl-job-core/src/main/java/com/xxl/job/core/executor/XxlJobExecutor.java
public class XxlJobExecutor {
    
    /**
     * 执行器启动入口
     */
    public void start() throws Exception {
        
        // 1、检查是否启用
        if (enabled != null && !enabled) {
            logger.info(">>>>>>>>>>> xxl-job executor start fail, enabled:{}", enabled);
            return;
        }
        
        // 2、初始化日志路径
        XxlJobFileAppender.initLogPath(logPath);
        
        // 3、初始化 Admin 客户端列表（用于后续注册和回调）
        initAdminBizList(adminAddresses, accessToken, timeout);
        
        // 4、启动日志清理线程
        JobLogFileCleanThread.getInstance().start(logRetentionDays);
        
        // 5、启动回调线程
        TriggerCallbackThread.getInstance().start();
        
        // 6、初始化并启动 Netty 服务器 ← 关键步骤
        initEmbedServer(address, ip, port, appname, accessToken);
    }
    
    /**
     * 初始化 Admin 客户端列表
     */
    private void initAdminBizList(String adminAddresses, String accessToken, int timeout) throws Exception {
        if (StringTool.isBlank(adminAddresses)) {
            return;
        }
        
        // 构建管理中心客户端列表
        for (String address : adminAddresses.trim().split(",")) {
            if (StringTool.isBlank(address)) {
                continue;
            }
            
            String finalAddress = address.trim();
            finalAddress = finalAddress.endsWith("/") ? (finalAddress + "api") : (finalAddress + "/api");
            int finalTimeout = (timeout >= 1 && timeout <= 10) ? timeout : 3;
            
            // 创建 HTTP 客户端代理
            AdminBiz adminBiz = HttpTool.createClient()
                    .url(finalAddress)
                    .timeout(finalTimeout * 1000)
                    .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken)
                    .proxy(AdminBiz.class);
            
            if (adminBizList == null) {
                adminBizList = new ArrayList<AdminBiz>();
            }
            adminBizList.add(adminBiz);
        }
    }
    
    /**
     * 初始化内嵌服务器
     */
    private void initEmbedServer(String address, String ip, int port, String appname, String accessToken) throws Exception {
        
        // 1、填充 IP 和端口
        port = port > 0 ? port : IPTool.getAvailablePort(9999);  // 默认端口 9999
        ip = StringTool.isNotBlank(ip) ? ip : IPTool.getIp();     // 自动获取本机 IP
        
        // 2、生成地址
        if (StringTool.isBlank(address)) {
            String ip_port_address = IPTool.toAddressString(ip, port);
            address = "http://{ip_port}/".replace("{ip_port}", ip_port_address);
        }
        
        // 3、检查 accessToken
        if (StringTool.isBlank(accessToken)) {
            logger.warn(">>>>>>>>>>> xxl-job accessToken is empty. To ensure system security, please set the accessToken.");
        }
        
        // 4、启动 EmbedServer ← Netty 服务器启动点
        embedServer = new EmbedServer();
        embedServer.start(address, port, appname, accessToken);
    }
}
```

### 2.5 Netty 服务器启动

#### **EmbedServer.start() 方法**
```java
// 文件位置：xxl-job-core/src/main/java/com/xxl/job/core/server/EmbedServer.java
public class EmbedServer {
    
    private ExecutorBiz executorBiz;
    private Thread thread;
    
    public void start(final String address, final int port, final String appname, final String accessToken) {
        
        // 1、创建业务处理器
        executorBiz = new ExecutorBizImpl();
        
        // 2、创建服务器线程
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                
                // 3、配置线程池
                EventLoopGroup bossGroup = new NioEventLoopGroup();
                EventLoopGroup workerGroup = new NioEventLoopGroup();
                
                ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(
                    0,                                              // 核心线程数
                    200,                                            // 最大线程数
                    60L,                                            // 空闲线程存活时间
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(2000),        // 任务队列
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            return new Thread(r, "xxl-job, EmbedServer bizThreadPool-" + r.hashCode());
                        }
                    },
                    new RejectedExecutionHandler() {
                        @Override
                        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                            throw new RuntimeException("xxl-job, EmbedServer bizThreadPool is EXHAUSTED!");
                        }
                    });
                
                try {
                    // 4、配置 Netty 服务器
                    ServerBootstrap bootstrap = new ServerBootstrap();
                    bootstrap.group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(new ChannelInitializer<SocketChannel>() {
                                @Override
                                public void initChannel(SocketChannel channel) throws Exception {
                                    channel.pipeline()
                                            // 心跳检测：90秒无读写自动断开
                                            .addLast(new IdleStateHandler(0, 0, 30 * 3, TimeUnit.SECONDS))
                                            // HTTP 编解码器
                                            .addLast(new HttpServerCodec())
                                            // HTTP 请求聚合：最大 5MB
                                            .addLast(new HttpObjectAggregator(5 * 1024 * 1024))
                                            // 业务处理器：处理管理中心的所有请求
                                            .addLast(new EmbedHttpServerHandler(executorBiz, accessToken, bizThreadPool));
                                }
                            })
                            .childOption(ChannelOption.SO_KEEPALIVE, true);
                    
                    // 5、绑定端口并启动服务器
                    ChannelFuture future = bootstrap.bind(port).sync();
                    
                    logger.info(">>>>>>>>>>> xxl-job remoting server start success, nettype = {}, port = {}", 
                              EmbedServer.class, port);
                    
                    // 6、启动注册线程 ← 执行器注册到管理中心
                    startRegistry(appname, address);
                    
                    // 7、等待服务器关闭
                    future.channel().closeFuture().sync();
                    
                } catch (InterruptedException e) {
                    logger.info(">>>>>>>>>>> xxl-job remoting server stop.");
                } catch (Throwable e) {
                    logger.error(">>>>>>>>>>> xxl-job remoting server error.", e);
                } finally {
                    // 8、优雅关闭
                    try {
                        workerGroup.shutdownGracefully();
                        bossGroup.shutdownGracefully();
                    } catch (Throwable e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        });
        
        // 9、设置为守护线程
        thread.setDaemon(true);
        thread.setName("xxl-job, EmbedServer");
        
        // 10、启动服务器线程
        thread.start();
    }
    
    /**
     * 启动注册线程
     */
    public void startRegistry(final String appname, final String address) {
        ExecutorRegistryThread.getInstance().start(appname, address);
    }
    
    /**
     * 停止注册线程
     */
    public void stopRegistry() {
        ExecutorRegistryThread.getInstance().toStop();
    }
}
```

### 2.6 注册线程启动

#### **ExecutorRegistryThread.start() 方法**
```java
// 文件位置：xxl-job-core/src/main/java/com/xxl/job/core/thread/ExecutorRegistryThread.java
public class ExecutorRegistryThread {
    
    private static ExecutorRegistryThread instance = new ExecutorRegistryThread();
    
    public static ExecutorRegistryThread getInstance() {
        return instance;
    }
    
    private Thread registryThread;
    private volatile boolean toStop = false;
    
    public void start(final String appname, final String address) {
        
        // 参数验证
        if (appname == null || appname.trim().length() == 0) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, appname is null.");
            return;
        }
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, adminAddresses is null.");
            return;
        }
        
        // 创建注册线程
        registryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                
                // 注册循环
                while (!toStop) {
                    try {
                        // 创建注册请求
                        RegistryRequest registryParam = new RegistryRequest(
                            RegistType.EXECUTOR.name(),  // 注册类型：EXECUTOR
                            appname,                      // 执行器应用名
                            address                       // 执行器地址
                        );
                        
                        // 向所有管理中心注册
                        for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
                            try {
                                Response<String> registryResult = adminBiz.registry(registryParam);
                                if (registryResult != null && registryResult.isSuccess()) {
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
                    
                    // 等待 30 秒后再次注册
                    try {
                        if (!toStop) {
                            TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);  // 30秒
                        }
                    } catch (Throwable e) {
                        if (!toStop) {
                            logger.warn(">>>>>>>>>>> xxl-job, executor registry thread interrupted");
                        }
                    }
                }
                
                // 停止时的清理工作
                try {
                    RegistryRequest registryParam = new RegistryRequest(
                        RegistType.EXECUTOR.name(), appname, address);
                    
                    for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
                        try {
                            Response<String> registryResult = adminBiz.registryRemove(registryParam);
                            if (registryResult != null && registryResult.isSuccess()) {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove success");
                                break;
                            }
                        } catch (Throwable e) {
                            if (!toStop) {
                                logger.info(">>>>>>>>>>> xxl-job registry-remove error", e);
                            }
                        }
                    }
                } catch (Throwable e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                
                logger.info(">>>>>>>>>>> xxl-job, executor registry thread destroy.");
            }
        });
        
        registryThread.setDaemon(true);
        registryThread.setName("xxl-job, executor ExecutorRegistryThread");
        registryThread.start();
    }
    
    public void toStop() {
        toStop = true;
        
        if (registryThread != null) {
            registryThread.interrupt();
            try {
                registryThread.join();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}
```

## 三、完整启动流程图

```
┌───────────────────────────────────────────────────────────────┐
│                    1. 应用启动                                │
│  Spring Boot Application.main()                               │
│  或 Frameless Application.main()                               │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    2. Spring 容器初始化                       │
│  - 读取配置文件（application.properties）                     │
│  - 创建 XxlJobConfig 配置类                                    │
│  - 实例化 XxlJobSpringExecutor Bean                           │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    3. Bean 初始化完成                          │
│  XxlJobSpringExecutor.afterSingletonsInstantiated()           │
│  - 扫描 @XxlJob 注解的方法                                    │
│  - 注册任务处理器到 jobHandlerRepository                      │
│  - 刷新 GlueFactory                                          │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    4. 执行器启动                               │
│  XxlJobExecutor.start()                                       │
│  - 检查 enabled 配置                                          │
│  - 初始化日志路径                                             │
│  - 初始化 Admin 客户端列表                                     │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    5. 启动辅助线程                             │
│  - JobLogFileCleanThread（日志清理）                          │
│  - TriggerCallbackThread（回调线程）                           │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    6. 初始化 Netty 服务器                      │
│  XxlJobExecutor.initEmbedServer()                              │
│  - 填充 IP 和端口配置                                          │
│  - 生成注册地址（http://ip:port/）                            │
│  - 验证 accessToken                                           │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    7. 创建 EmbedServer                        │
│  EmbedServer.start()                                           │
│  - 创建 ExecutorBizImpl 业务处理器                             │
│  - 创建 Netty 服务器线程                                       │
│  - 配置 EventLoopGroup（Boss + Worker）                       │
│  - 配置业务线程池（max 200 线程）                               │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    8. 配置 Netty Pipeline                     │
│  ServerBootstrap 配置：                                         │
│  - IdleStateHandler（心跳检测，90秒超时）                      │
│  - HttpServerCodec（HTTP 编解码）                              │
│  - HttpObjectAggregator（请求聚合，最大5MB）                    │
│  - EmbedHttpServerHandler（业务处理器）                        │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                    9. 启动 Netty 服务器                         │
│  bootstrap.bind(port).sync()                                  │
│  - 绑定指定端口（默认 9999）                                   │
│  - 启动 Netty 服务器                                          │
│  - 输出启动成功日志                                           │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                   10. 启动注册线程                             │
│  EmbedServer.startRegistry(appname, address)                   │
│  ExecutorRegistryThread.start()                                │
│  - 创建注册线程                                               │
│  - 每 30 秒向管理中心注册一次                                   │
│  - 注册信息：EXECUTOR、appname、address                        │
└────────────────────┬──────────────────────────────────────────┘
                     │
┌────────────────────▼──────────────────────────────────────────┐
│                   11. 服务器运行状态                          │
│  - Netty 服务器监听端口，接收管理中心请求                       │
│  - 注册线程定期向管理中心注册                                  │
│  - 回调线程处理任务结果回调                                     │
│  - 日志清理线程定期清理过期日志                                 │
└───────────────────────────────────────────────────────────────┘
```

## 四、关键代码文件路径汇总

### 4.1 Spring Boot 版本
| 组件 | 文件路径 |
|------|----------|
| **应用入口** | `xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/java/com/xxl/job/executor/XxlJobExecutorApplication.java` |
| **配置类** | `xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/java/com/xxl/job/executor/config/XxlJobConfig.java` |
| **配置文件** | `xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/resources/application.properties` |

### 4.2 核心执行器
| 组件 | 文件路径 |
|------|----------|
| **Spring 执行器** | `xxl-job-core/src/main/java/com/xxl/job/core/executor/impl/XxlJobSpringExecutor.java` |
| **简单执行器** | `xxl-job-core/src/main/java/com/xxl/job/core/executor/impl/XxlJobSimpleExecutor.java` |
| **基础执行器** | `xxl-job-core/src/main/java/com/xxl/job/core/executor/XxlJobExecutor.java` |

### 4.3 Netty 服务器
| 组件 | 文件路径 |
|------|----------|
| **内嵌服务器** | `xxl-job-core/src/main/java/com/xxl/job/core/server/EmbedServer.java` |
| **HTTP 处理器** | `EmbedServer.EmbedHttpServerHandler` (内部类) |
| **业务实现** | `xxl-job-core/src/main/java/com/xxl/job/core/openapi/impl/ExecutorBizImpl.java` |

### 4.4 辅助线程
| 组件 | 文件路径 |
|------|----------|
| **注册线程** | `xxl-job-core/src/main/java/com/xxl/job/core/thread/ExecutorRegistryThread.java` |
| **回调线程** | `xxl-job-core/src/main/java/com/xxl/job/core/thread/TriggerCallbackThread.java` |
| **日志清理** | `xxl-job-core/src/main/java/com/xxl/job/core/thread/JobLogFileCleanThread.java` |
| **任务线程** | `xxl-job-core/src/main/java/com/xxl/job/core/thread/JobThread.java` |

## 五、启动顺序总结

### 5.1 Spring Boot 版本启动顺序
1. **Spring Boot Application 启动**
2. **读取 application.properties 配置文件**
3. **创建 XxlJobConfig @Configuration 类**
4. **实例化 XxlJobSpringExecutor @Bean**
5. **Spring 容器初始化完成，调用 afterSingletonsInstantiated()**
6. **扫描 @XxlJob 注解的方法并注册**
7. **调用 XxlJobExecutor.start()**
8. **启动 Netty EmbedServer**
9. **启动 ExecutorRegistryThread 注册线程**
10. **系统准备就绪，等待管理中心调度**

### 5.2 Frameless 版本启动顺序
1. **Main 方法直接启动**
2. **创建 XxlJobSimpleExecutor 实例**
3. **设置配置参数（通过 setter 方法）**
4. **调用 XxlJobSimpleExecutor.start()**
5. **启动 Netty EmbedServer**
6. **启动 ExecutorRegistryThread 注册线程**
7. **系统准备就绪，等待管理中心调度**

## 六、关键时间点和注意事项

### 6.1 启动时间点
- **Netty 服务器启动**：在 `XxlJobExecutor.start()` 方法中的 `initEmbedServer()` 调用时
- **首次注册**：在 Netty 服务器启动成功后立即开始
- **Bean 扫描**：在 Spring 容器初始化完成后进行

### 6.2 端口配置
- **默认端口**：9999
- **端口获取策略**：如果配置的端口 ≤ 0，则自动获取可用端口
- **IP 获取策略**：如果没有配置 IP，自动获取本机 IP

### 6.3 线程配置
- **Netty Boss 线程**：1 个（默认）
- **Netty Worker 线程**：CPU 核心数 × 2（默认）
- **业务线程池**：0-200 个线程
- **注册线程**：1 个守护线程

### 6.4 优雅停机
- **等待时间**：5 秒优雅停机等待时间
- **线程清理**：先停止 Netty 服务器，再清理任务线程，最后停止辅助线程
- **注册清理**：停机时会主动向管理中心发送注册移除请求

通过这个完整的路径梳理，可以清晰地看到 XXL-JOB 执行器从应用启动到 Netty 服务器运行的完整过程，每个环节都有明确的代码位置和调用关系。