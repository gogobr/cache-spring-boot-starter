# 缓存框架测试指南

本文档提供详细的测试指南，帮助您验证缓存框架是否正常工作并符合预期。

## 📋 目录

- [快速测试](#快速测试)
- [详细测试步骤](#详细测试步骤)
- [测试用例说明](#测试用例说明)
- [手动测试方法](#手动测试方法)
- [性能测试](#性能测试)
- [常见问题排查](#常见问题排查)

## 🚀 快速测试

### 1. 运行单元测试

```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=CacheFrameworkTest

# 运行特定测试方法
mvn test -Dtest=CacheFrameworkTest#testBasicCache
```

### 2. 查看测试结果

测试通过后，您应该看到类似以下输出：

```
✅ 基础缓存测试通过：方法只被调用了一次
✅ 条件缓存测试通过
✅ 动态过期时间测试通过
✅ 批量缓存测试通过
✅ 并发缓存测试通过
✅ 仅本地缓存测试通过
```

## 📝 详细测试步骤

### 测试环境准备

1. **确保 Redis 已启动**（如果使用 Redis）
   ```bash
   # 检查 Redis 是否运行
   redis-cli ping
   # 应该返回: PONG
   ```

2. **配置测试环境**
   
   创建 `src/test/resources/application-test.yml`:
   ```yaml
   spring:
     data:
       redis:
         host: localhost
         port: 6379
         database: 0
   
   cache:
     default-expire: 60
     default-local-expire: 30
   ```

### 测试1：基础缓存功能

**目标**：验证缓存基本功能是否生效

**步骤**：

1. 创建一个测试服务类：
```java
@Service
public class TestService {
    private int callCount = 0;
    
    @Cacheable(cacheNames = {"test"}, key = "#id")
    public String getData(Long id) {
        callCount++;
        System.out.println("方法被调用，次数: " + callCount);
        return "Data-" + id;
    }
}
```

2. 调用测试方法：
```java
@Autowired
private TestService testService;

@Test
public void testBasicCache() {
    // 第一次调用
    String result1 = testService.getData(1L);
    // 应该看到日志：方法被调用，次数: 1
    
    // 第二次调用（应该从缓存获取）
    String result2 = testService.getData(1L);
    // 不应该看到新的日志输出
    
    // 验证结果一致
    assertEquals(result1, result2);
}
```

**预期结果**：
- ✅ 第一次调用执行方法
- ✅ 第二次调用不执行方法，从缓存获取
- ✅ 两次返回结果相同

### 测试2：验证缓存命中

**目标**：验证缓存确实在工作

**方法1：观察日志**

启用 DEBUG 日志级别：
```yaml
logging:
  level:
    com.mx.cache: DEBUG
```

查看日志输出，应该看到：
```
DEBUG - Cache hit for key: test::1
```

**方法2：使用调用计数**

```java
@Service
public class TestService {
    private final AtomicInteger callCount = new AtomicInteger(0);
    
    @Cacheable(cacheNames = {"test"}, key = "#id")
    public String getData(Long id) {
        int count = callCount.incrementAndGet();
        log.info("方法被调用，当前计数: {}", count);
        return "Data-" + id;
    }
    
    public int getCallCount() {
        return callCount.get();
    }
}

// 测试
@Test
public void testCacheHit() {
    testService.getData(1L);
    assertEquals(1, testService.getCallCount());
    
    testService.getData(1L); // 应该从缓存获取
    assertEquals(1, testService.getCallCount()); // 计数不应该增加
}
```

### 测试3：多级缓存验证

**目标**：验证本地缓存和远程缓存都正常工作

**步骤**：

1. **测试本地缓存**
```java
@Cacheable(
    cacheNames = {"test"},
    key = "#id",
    cacheLevels = "local"  // 仅使用本地缓存
)
public String getLocalData(Long id) {
    return "Local-" + id;
}
```

2. **测试远程缓存**
```java
@Cacheable(
    cacheNames = {"test"},
    key = "#id",
    cacheLevels = "remote"  // 仅使用远程缓存
)
public String getRemoteData(Long id) {
    return "Remote-" + id;
}
```

3. **验证 Redis 中的数据**
```bash
# 连接 Redis
redis-cli

# 查看所有 key
KEYS test::*

# 查看特定 key 的值
GET test::1
```

### 测试4：批量缓存验证

**目标**：验证批量查询时，已缓存的数据不会重复查询

**步骤**：

```java
// 1. 先单独查询几个用户
userService.getUser(1L);
userService.getUser(2L);

// 2. 批量查询（包含已缓存和未缓存的）
List<User> users = userService.getUsersByIds(
    Arrays.asList(1L, 2L, 3L, 4L)
);

// 3. 验证：批量查询方法应该只查询 3L 和 4L
// （1L 和 2L 应该从缓存获取）
```

**预期结果**：
- ✅ 已缓存的用户（1L, 2L）从缓存获取
- ✅ 未缓存的用户（3L, 4L）通过批量查询方法获取
- ✅ 最终返回完整的用户列表

### 测试5：并发测试

**目标**：验证高并发场景下缓存正常工作

**步骤**：

```java
@Test
public void testConcurrent() throws InterruptedException {
    int threadCount = 10;
    int requestsPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                for (int j = 0; j < requestsPerThread; j++) {
                    userService.getUser(1L);
                }
            } finally {
                latch.countDown();
            }
        });
    }
    
    latch.await();
    
    // 验证：方法应该只被调用一次
    assertEquals(1, userService.getCallCount());
}
```

**预期结果**：
- ✅ 所有请求都能正常返回
- ✅ 方法只被调用一次（第一次）
- ✅ 无异常或错误

## 🔍 测试用例说明

### 测试用例列表

| 测试用例 | 测试内容 | 验证点 |
|---------|---------|--------|
| `testBasicCache` | 基础缓存功能 | 缓存命中、结果一致性 |
| `testConditionalCache` | 条件缓存 | 条件表达式生效 |
| `testDynamicExpire` | 动态过期时间 | SpEL 表达式计算过期时间 |
| `testBatchCache` | 批量缓存 | 批量查询优化 |
| `testConcurrentCache` | 并发缓存 | 并发安全性 |
| `testCacheExpire` | 缓存过期 | 过期后重新查询 |
| `testLocalOnlyCache` | 仅本地缓存 | 本地缓存独立工作 |

## 🖐️ 手动测试方法

### 方法1：使用 Spring Boot Test

创建一个简单的测试应用：

```java
@SpringBootApplication
public class CacheTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(CacheTestApplication.class, args);
    }
}

@RestController
public class TestController {
    @Autowired
    private TestUserService userService;
    
    @GetMapping("/test/{id}")
    public String test(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        TestUser user = userService.getUser(id);
        long duration = System.currentTimeMillis() - start;
        return String.format("用户: %s, 耗时: %dms, 调用次数: %d", 
            user.getName(), duration, userService.getGetUserCallCount());
    }
}
```

**测试步骤**：

1. 启动应用
2. 第一次访问：`curl http://localhost:8080/test/1`
   - 应该看到较长的耗时（模拟数据库查询）
   - 调用次数 = 1
3. 第二次访问：`curl http://localhost:8080/test/1`
   - 应该看到很短的耗时（从缓存获取）
   - 调用次数仍然 = 1

### 方法2：使用日志观察

启用详细日志：

```yaml
logging:
  level:
    com.mx.cache: DEBUG
    root: INFO
```

观察日志输出：

```
# 第一次调用
INFO  - === 执行 getUser 方法，userId: 1, 调用次数: 1 ===

# 第二次调用（应该没有这条日志，因为从缓存获取）
```

### 方法3：使用 Redis 客户端

如果使用 Redis，可以直接查看缓存数据：

```bash
# 连接 Redis
redis-cli

# 查看所有缓存 key
KEYS *::*

# 查看特定 key
GET test-user::1

# 查看 key 的 TTL
TTL test-user::1
```

## ⚡ 性能测试

### 测试缓存性能提升

```java
@Test
public void testPerformance() {
    int iterations = 1000;
    
    // 测试无缓存性能
    long start1 = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
        expensiveOperation(); // 不使用缓存的方法
    }
    long duration1 = System.currentTimeMillis() - start1;
    
    // 测试有缓存性能
    long start2 = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
        cachedOperation(); // 使用缓存的方法
    }
    long duration2 = System.currentTimeMillis() - start2;
    
    double improvement = (double)(duration1 - duration2) / duration1 * 100;
    System.out.println(String.format(
        "性能提升: %.2f%%, 无缓存: %dms, 有缓存: %dms", 
        improvement, duration1, duration2
    ));
}
```

**预期结果**：
- ✅ 有缓存的性能应该显著优于无缓存
- ✅ 第一次调用后，后续调用应该非常快（< 1ms）

## 🐛 常见问题排查

### 问题1：缓存不生效

**症状**：每次调用都执行方法

**排查步骤**：

1. ✅ 检查方法是否是 Spring Bean
   ```java
   // ✅ 正确
   @Service
   public class UserService { ... }
   
   // ❌ 错误
   public class UserService { ... }  // 不是 Spring Bean
   ```

2. ✅ 检查是否通过 Spring 代理调用
   ```java
   // ✅ 正确
   @Autowired
   private UserService userService;
   userService.getUser(1L);
   
   // ❌ 错误
   this.getUser(1L);  // 同类内部调用，AOP 不生效
   ```

3. ✅ 检查条件表达式
   ```java
   @Cacheable(
       cacheNames = {"test"},
       key = "#id",
       condition = "#id > 0"  // 确保条件为 true
   )
   ```

4. ✅ 检查日志
   - 启用 DEBUG 日志查看缓存操作
   - 查看是否有异常或错误

### 问题2：Redis 连接失败

**症状**：应用启动正常，但远程缓存不工作

**排查步骤**：

1. ✅ 检查 Redis 是否运行
   ```bash
   redis-cli ping
   ```

2. ✅ 检查 Redis 配置
   ```yaml
   spring:
     data:
       redis:
         host: localhost
         port: 6379
   ```

3. ✅ 查看日志
   - 查找 "Redis health check failed" 相关日志
   - 框架会自动降级为仅本地缓存

### 问题3：缓存数据不一致

**症状**：缓存的数据与数据库不一致

**排查步骤**：

1. ✅ 检查缓存过期时间设置
2. ✅ 检查是否有其他地方修改了数据但未清除缓存
3. ✅ 使用 `@CacheRefresh` 定时刷新缓存

### 问题4：内存占用过高

**症状**：应用内存使用持续增长

**排查步骤**：

1. ✅ 检查本地缓存配置
   ```java
   @Cacheable(
       cacheNames = {"test"},
       key = "#id",
       maxSize = 10000,  // 限制最大条目数
       maxWeight = 10485760  // 限制最大权重
   )
   ```

2. ✅ 调整过期时间
   - 缩短本地缓存过期时间
   - 缩短远程缓存过期时间

3. ✅ 使用合适的淘汰策略
   ```java
   evictionPolicy = Cacheable.EvictionPolicy.LRU  // 使用 LRU
   ```

## 📊 测试检查清单

使用以下清单确保所有功能都经过测试：

- [ ] 基础缓存功能
  - [ ] 第一次调用执行方法
  - [ ] 第二次调用从缓存获取
  - [ ] 返回结果一致

- [ ] 条件缓存
  - [ ] 满足条件时缓存
  - [ ] 不满足条件时不缓存

- [ ] 动态过期时间
  - [ ] SpEL 表达式计算过期时间
  - [ ] 从结果字段获取过期时间

- [ ] 批量缓存
  - [ ] 已缓存数据从缓存获取
  - [ ] 未缓存数据批量查询
  - [ ] 结果顺序正确

- [ ] 并发测试
  - [ ] 高并发下正常工作
  - [ ] 无数据竞争
  - [ ] 性能稳定

- [ ] 多级缓存
  - [ ] 本地缓存工作正常
  - [ ] 远程缓存工作正常（如果配置）
  - [ ] 缓存层级配置生效

- [ ] 压缩功能
  - [ ] 大对象自动压缩
  - [ ] 压缩后数据正确

- [ ] 热点 Key 保护
  - [ ] 分布式锁生效（需要 Redis）
  - [ ] 防止缓存击穿

## 🎯 测试最佳实践

1. **使用独立的测试环境**
   - 使用测试专用的 Redis 数据库
   - 避免影响生产数据

2. **清理测试数据**
   ```java
   @AfterEach
   public void tearDown() {
       // 清理测试数据
       redisTemplate.delete("test::*");
   }
   ```

3. **使用断言验证**
   - 不要只依赖日志
   - 使用 JUnit 断言验证结果

4. **测试边界情况**
   - null 值处理
   - 空集合处理
   - 异常情况处理

5. **性能基准测试**
   - 记录性能指标
   - 对比有无缓存的性能差异

## 📞 获取帮助

如果测试过程中遇到问题：

1. 查看日志输出
2. 检查配置是否正确
3. 参考本文档的常见问题部分
4. 提交 Issue 或联系维护团队

---

**最后更新**: 2024年



