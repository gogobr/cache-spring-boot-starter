# Cache Spring Boot Starter 使用文档

## 📖 项目介绍

`cache-spring-boot-starter` 是一个功能强大的 Spring Boot 缓存增强框架，提供了多级缓存、缓存预热、定时刷新、批量缓存等高级特性。

### 核心特性

- ✅ **多级缓存架构**：本地缓存（Caffeine）+ 远程缓存（Redis）
- ✅ **智能缓存策略**：支持 LRU、LFU、FIFO、WEIGHT 多种淘汰策略
- ✅ **缓存预热**：应用启动时自动预热热点数据
- ✅ **定时刷新**：支持全量和增量两种刷新模式
- ✅ **批量缓存**：优化批量查询场景，自动分离已缓存和未缓存数据
- ✅ **防缓存穿透**：内置布隆过滤器
- ✅ **数据压缩**：大对象自动压缩，节省存储空间
- ✅ **热点 Key 保护**：分布式锁防止缓存击穿
- ✅ **动态过期时间**：支持 SpEL 表达式和结果字段动态计算过期时间
- ✅ **Redis 可选**：支持仅使用本地缓存，无需 Redis

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.hxl.cache</groupId>
    <artifactId>cache-spring-boot-starter</artifactId>
    <version>1.3-SNAPSHOT</version>
</dependency>
```
添加扫描包 `com.hxl` ：

### 2. 配置 Redis（可选）

如果使用 Redis 作为远程缓存，需要配置 Redis 连接：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: 
      database: 0
```

> **注意**：如果不配置 Redis，框架会自动降级为仅使用本地缓存。

### 3. 基础使用示例

```java
@Service
public class UserService {
    
    @Cacheable(
        cacheNames = {"user"},
        key = "#userId",
        expire = 3600,
        expireUnit = TimeUnit.SECONDS
    )
    public User getUserById(Long userId) {
        // 从数据库查询用户
        return userRepository.findById(userId);
    }
}
```

## 📝 注解详解

### @Cacheable - 单条缓存

最常用的缓存注解，用于缓存方法返回值。

#### 基础属性

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `cacheNames` | String[] | ✅ | - | 缓存名称数组 |
| `key` | String | ❌ | `""` | 缓存 Key，支持 SpEL 表达式 |
| `condition` | String | ❌ | `""` | 缓存条件，支持 SpEL 表达式 |

#### 过期时间配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `expire` | long | 3600 | 远程缓存过期时间 |
| `expireUnit` | TimeUnit | SECONDS | 过期时间单位 |
| `spelExpire` | String | `""` | 动态过期时间 SpEL 表达式 |
| `resultFieldExpire` | String | `""` | 从结果对象字段获取过期时间 |

#### 本地缓存配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `localExpire` | long | 600 | 本地缓存过期时间（秒） |
| `localExpireUnit` | TimeUnit | SECONDS | 本地缓存过期时间单位 |
| `cacheLevels` | String | `"local,remote"` | 缓存层级，可选：`local`、`remote`、`local,remote` |

#### 压缩配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `zip` | boolean | false | 是否启用压缩 |
| `zipThreshold` | int | 1024 | 压缩阈值（字节），超过此大小才压缩 |

#### 淘汰策略

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `evictionPolicy` | EvictionPolicy | LRU | 淘汰策略：LRU、LFU、FIFO、WEIGHT |
| `maxSize` | long | 10000 | 最大缓存条目数 |
| `maxWeight` | long | 10485760 | 最大权重（字节），WEIGHT 策略使用 |

#### 其他配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maxKeySize` | int | 256 | 最大 Key 长度（字节） |
| `rejectLargeKey` | boolean | false | 是否拒绝超大 Key |
| `cacheNull` | boolean | true | 是否缓存 null 值 |
| `hotKey` | boolean | false | 是否为热点 Key（启用分布式锁保护） |

#### 使用示例

```java
@Service
public class ProductService {
    
    // 基础用法
    @Cacheable(
        cacheNames = {"product"},
        key = "#productId"
    )
    public Product getProduct(Long productId) {
        return productRepository.findById(productId);
    }
    
    // 使用 SpEL 表达式生成 Key
    @Cacheable(
        cacheNames = {"product"},
        key = "'product:' + #productId + ':' + #type"
    )
    public Product getProductByType(Long productId, String type) {
        return productRepository.findByProductIdAndType(productId, type);
    }
    
    // 条件缓存
    @Cacheable(
        cacheNames = {"product"},
        key = "#productId",
        condition = "#productId > 0"
    )
    public Product getProductWithCondition(Long productId) {
        return productRepository.findById(productId);
    }
    
    // 动态过期时间
    @Cacheable(
        cacheNames = {"product"},
        key = "#productId",
        spelExpire = "#ttl != null ? #ttl : 3600"
    )
    public Product getProductWithDynamicExpire(Long productId, Integer ttl) {
        return productRepository.findById(productId);
    }
    
    // 从结果字段获取过期时间
    @Cacheable(
        cacheNames = {"product"},
        key = "#productId",
        resultFieldExpire = "expireTime"
    )
    public Product getProductWithFieldExpire(Long productId) {
        // Product 对象需要有 expireTime 字段（Long 类型，时间戳）
        return productRepository.findById(productId);
    }
    
    // 启用压缩（适合大对象）
    @Cacheable(
        cacheNames = {"product"},
        key = "#productId",
        zip = true,
        zipThreshold = 2048
    )
    public ProductDetail getProductDetail(Long productId) {
        return productRepository.findDetailById(productId);
    }
    
    // 热点 Key 保护
    @Cacheable(
        cacheNames = {"hot-product"},
        key = "#productId",
        hotKey = true
    )
    public Product getHotProduct(Long productId) {
        // 高并发场景下的热点商品
        return productRepository.findById(productId);
    }
    
    // 仅使用本地缓存
    @Cacheable(
        cacheNames = {"local-cache"},
        key = "#key",
        cacheLevels = "local"
    )
    public String getLocalData(String key) {
        return expensiveOperation(key);
    }
    
    // 自定义淘汰策略
    @Cacheable(
        cacheNames = {"weight-cache"},
        key = "#key",
        evictionPolicy = Cacheable.EvictionPolicy.WEIGHT,
        maxWeight = 52428800  // 50MB
    )
    public LargeObject getLargeObject(String key) {
        return loadLargeObject(key);
    }
}
```

### @CacheableBatch - 批量缓存

用于优化批量查询场景，自动分离已缓存和未缓存的数据，只查询未缓存的数据。

#### 属性说明

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `cacheNames` | String[] | ✅ | 缓存名称数组 |
| `itemKey` | String | ✅ | 单个元素的 Key SpEL 表达式 |
| `batchMethod` | String | ✅ | 批量查询方法名 |
| `itemType` | Class<?> | ✅ | 单个元素的类型 |
| `expire` | long | ❌ | 过期时间（默认 3600 秒） |
| `expireUnit` | TimeUnit | ❌ | 过期时间单位（默认 SECONDS） |
| `zip` | boolean | ❌ | 是否启用压缩（默认 false） |
| `zipThreshold` | int | ❌ | 压缩阈值（默认 1024 字节） |
| `maxKeySize` | int | ❌ | 最大 Key 长度（默认 256 字节） |

#### 使用示例

```java
@Service
public class UserService {
    
    // 单个查询方法（会被缓存）
    @Cacheable(
        cacheNames = {"user"},
        key = "#userId"
    )
    public User getUserById(Long userId) {
        return userRepository.findById(userId);
    }
    
    // 批量查询方法（使用批量缓存）
    @CacheableBatch(
        cacheNames = {"user"},
        itemKey = "#id",
        batchMethod = "batchGetUsersByIds",
        itemType = User.class
    )
    public List<User> getUsersByIds(List<Long> ids) {
        // 框架会自动：
        // 1. 从缓存中查询已存在的用户
        // 2. 只查询未缓存的用户ID
        // 3. 将新查询的结果存入缓存
        // 4. 合并结果并保持原有顺序
        return batchGetUsersByIds(ids);
    }
    
    // 批量查询的底层方法
    public List<User> batchGetUsersByIds(List<Long> ids) {
        return userRepository.findByIds(ids);
    }
}
```

### @CachePreload - 缓存预热

在应用启动时或方法首次调用时，自动预热缓存。

#### 属性说明

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `params` | String | ✅ | - | 预热参数的 SpEL 表达式 |
| `delay` | long | ❌ | 0 | 延迟执行时间（毫秒） |
| `retryCount` | int | ❌ | 1 | 重试次数 |
| `retryInterval` | long | ❌ | 1000 | 重试间隔（毫秒） |
| `group` | String | ❌ | `"default"` | 预热任务分组 |
| `async` | boolean | ❌ | true | 是否异步执行 |

#### 使用示例

```java
@Service
public class ConfigService {
    
    @Cacheable(
        cacheNames = {"config"},
        key = "#configKey"
    )
    @CachePreload(
        params = "{'app.name', 'app.version', 'app.env'}",
        delay = 1000,
        async = true,
        retryCount = 3
    )
    public String getConfig(String configKey) {
        return configRepository.findByKey(configKey);
    }
    
    // 同步预热
    @Cacheable(
        cacheNames = {"hot-data"},
        key = "#id"
    )
    @CachePreload(
        params = "#ids",
        async = false
    )
    public HotData getHotData(Long id) {
        return hotDataRepository.findById(id);
    }
}
```

### @CacheRefresh - 定时刷新

定时刷新缓存数据，支持全量和增量两种模式。

#### 属性说明

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `params` | String | ✅ | - | 刷新参数的 SpEL 表达式 |
| `period` | long | ✅ | - | 刷新周期 |
| `periodUnit` | TimeUnit | ✅ | - | 刷新周期单位 |
| `initialRefresh` | boolean | ❌ | true | 是否立即执行一次 |
| `mode` | String | ❌ | `"FULL"` | 刷新模式：`FULL`（全量）或 `INCREMENTAL`（增量） |

#### 使用示例

```java
@Service
public class DataService {
    
    // 全量刷新（每 5 分钟刷新一次）
    @Cacheable(
        cacheNames = {"data"},
        key = "#id"
    )
    @CacheRefresh(
        params = "#ids",
        period = 5,
        periodUnit = TimeUnit.MINUTES,
        mode = "FULL"
    )
    public Data getData(Long id) {
        return dataRepository.findById(id);
    }
    
    // 增量刷新（每小时刷新一次，延迟 10 分钟首次执行）
    @Cacheable(
        cacheNames = {"incremental-data"},
        key = "#id"
    )
    @CacheRefresh(
        params = "#ids",
        period = 1,
        periodUnit = TimeUnit.HOURS,
        initialRefresh = false,
        mode = "INCREMENTAL"
    )
    public IncrementalData getIncrementalData(Long id) {
        return incrementalDataRepository.findById(id);
    }
}
```

## ⚙️ 配置说明

### 应用配置

在 `application.yml` 或 `application.properties` 中配置：

```yaml
cache:
  # 缓存切面优先级
  aspect-order: 100
  # 批量缓存切面优先级
  batch-aspect-order: 101
  # 缓存调度线程池大小
  scheduler-pool-size: 5
  # 缓存执行线程池大小
  executor-pool-size: 10
  # 预热任务重复执行间隔（毫秒）
  preload-interval: 86400000
  # 默认过期时间（秒）
  default-expire: 3600
  # 默认本地缓存过期时间（秒）
  default-local-expire: 600
  # 默认缓存层级
  default-cache-levels: local,remote
  # 布隆过滤器配置
  bloom-filter:
    # 预期插入数量
    expected-insertions: 1000000
    # 误判率
    false-positive-rate: 0.01
    # 自动刷新间隔（分钟）
    refresh-interval: 60
```

### Redis 配置（可选）

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: your-password
      database: 0
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
```

## 💡 使用示例

### 完整示例

```java
@Service
@Slf4j
public class OrderService {
    
    @Autowired
    private OrderRepository orderRepository;
    
    /**
     * 基础缓存：查询订单
     */
    @Cacheable(
        cacheNames = {"order"},
        key = "#orderId",
        expire = 1800,  // 30分钟
        expireUnit = TimeUnit.SECONDS
    )
    public Order getOrder(Long orderId) {
        log.info("查询订单: {}", orderId);
        return orderRepository.findById(orderId);
    }
    
    /**
     * 条件缓存：只缓存已支付订单
     */
    @Cacheable(
        cacheNames = {"order"},
        key = "#orderId",
        condition = "#result != null && #result.status == 'PAID'"
    )
    public Order getPaidOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }
    
    /**
     * 批量查询：自动分离已缓存和未缓存数据
     */
    @CacheableBatch(
        cacheNames = {"order"},
        itemKey = "#id",
        batchMethod = "batchGetOrdersByIds",
        itemType = Order.class,
        expire = 1800
    )
    public List<Order> getOrdersByIds(List<Long> ids) {
        return batchGetOrdersByIds(ids);
    }
    
    private List<Order> batchGetOrdersByIds(List<Long> ids) {
        return orderRepository.findByIds(ids);
    }
    
    /**
     * 热点数据：启用分布式锁保护
     */
    @Cacheable(
        cacheNames = {"hot-order"},
        key = "#orderId",
        hotKey = true,
        expire = 600
    )
    public Order getHotOrder(Long orderId) {
        // 高并发场景下的热点订单
        return orderRepository.findById(orderId);
    }
    
    /**
     * 大对象：启用压缩
     */
    @Cacheable(
        cacheNames = {"order-detail"},
        key = "#orderId",
        zip = true,
        zipThreshold = 2048,  // 超过 2KB 才压缩
        expire = 3600
    )
    public OrderDetail getOrderDetail(Long orderId) {
        return orderRepository.findDetailById(orderId);
    }
    
    /**
     * 缓存预热：应用启动时预热热门订单
     */
    @Cacheable(
        cacheNames = {"order"},
        key = "#orderId"
    )
    @CachePreload(
        params = "{1001L, 1002L, 1003L}",
        delay = 2000,
        async = true,
        retryCount = 3
    )
    public Order getPopularOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }
    
    /**
     * 定时刷新：每 10 分钟刷新一次订单状态
     */
    @Cacheable(
        cacheNames = {"order-status"},
        key = "#orderId"
    )
    @CacheRefresh(
        params = "#orderIds",
        period = 10,
        periodUnit = TimeUnit.MINUTES,
        mode = "FULL"
    )
    public OrderStatus getOrderStatus(Long orderId) {
        return orderRepository.findStatusById(orderId);
    }
}
```

## 🎯 最佳实践

### 1. Key 设计原则

- ✅ **使用有意义的 Key**：包含业务含义，便于排查问题
- ✅ **避免 Key 冲突**：使用缓存名称作为前缀
- ✅ **控制 Key 长度**：避免过长的 Key 影响性能

```java
// ✅ 好的 Key 设计
@Cacheable(
    cacheNames = {"user"},
    key = "'user:' + #userId"
)

// ❌ 不好的 Key 设计
@Cacheable(
    cacheNames = {"user"},
    key = "#userId"  // 可能与其他缓存冲突
)
```

### 2. 过期时间设置

- **热点数据**：设置较短的过期时间（5-30 分钟）
- **冷数据**：设置较长的过期时间（1-24 小时）
- **配置数据**：可以设置很长的过期时间（1-7 天）

```java
// 热点数据
@Cacheable(cacheNames = {"hot"}, key = "#id", expire = 600)

// 普通数据
@Cacheable(cacheNames = {"normal"}, key = "#id", expire = 3600)

// 配置数据
@Cacheable(cacheNames = {"config"}, key = "#key", expire = 86400)
```

### 3. 缓存层级选择

- **高频访问数据**：使用 `local,remote`（默认）
- **仅本地数据**：使用 `local`（无需 Redis）
- **仅远程数据**：使用 `remote`（多实例共享）

```java
// 高频访问
@Cacheable(cacheNames = {"hot"}, key = "#id", cacheLevels = "local,remote")

// 仅本地（无需 Redis）
@Cacheable(cacheNames = {"local"}, key = "#id", cacheLevels = "local")
```

### 4. 压缩使用场景

- ✅ **大对象**：超过 1KB 的对象考虑启用压缩
- ✅ **文本数据**：JSON、XML 等文本数据压缩效果好
- ❌ **小对象**：小于 1KB 的对象不建议压缩（压缩开销大于收益）

```java
// 大对象启用压缩
@Cacheable(
    cacheNames = {"large"},
    key = "#id",
    zip = true,
    zipThreshold = 1024
)
```

### 5. 热点 Key 保护

对于高并发场景下的热点数据，启用 `hotKey = true`：

```java
@Cacheable(
    cacheNames = {"hot-product"},
    key = "#productId",
    hotKey = true  // 启用分布式锁保护
)
public Product getHotProduct(Long productId) {
    // 防止缓存击穿
}
```

### 6. 批量查询优化

使用 `@CacheableBatch` 优化批量查询场景：

```java
// ✅ 使用批量缓存
@CacheableBatch(
    cacheNames = {"user"},
    itemKey = "#id",
    batchMethod = "batchGetUsers",
    itemType = User.class
)
public List<User> getUsers(List<Long> ids) {
    return batchGetUsers(ids);
}

// ❌ 避免循环调用单个缓存方法
public List<User> getUsersBad(List<Long> ids) {
    return ids.stream()
        .map(this::getUserById)  // 多次网络请求
        .collect(Collectors.toList());
}
```

### 7. 缓存预热策略

- **关键数据**：应用启动时预热
- **大量数据**：延迟预热，避免启动过慢
- **失败重试**：设置合理的重试次数和间隔

```java
@CachePreload(
    params = "{'key1', 'key2', 'key3'}",
    delay = 5000,  // 延迟 5 秒，避免影响启动速度
    async = true,
    retryCount = 3
)
```

## ❓ 常见问题

### Q1: 缓存不生效？

**可能原因：**
1. 方法不是 Spring Bean（必须是 `@Service`、`@Component` 等）
2. 方法被同类内部调用（AOP 不生效）
3. 条件表达式返回 false

**解决方案：**
```java
// ✅ 正确：通过 Spring 代理调用
@Autowired
private UserService userService;

public void test() {
    userService.getUser(1L);  // 缓存生效
}

// ❌ 错误：同类内部调用
public void test() {
    getUser(1L);  // 缓存不生效
}
```

### Q2: Redis 连接失败？

框架会自动降级为仅使用本地缓存，不影响应用启动。检查 Redis 配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### Q3: 如何清除缓存？

框架暂未提供清除缓存的注解，可以通过以下方式：

1. **等待过期**：让缓存自然过期
2. **重启应用**：清除本地缓存
3. **Redis 命令**：直接删除 Redis 中的 Key

### Q4: 缓存穿透如何防护？

框架内置了布隆过滤器，当 `cacheNull = false` 时会自动启用：

```java
@Cacheable(
    cacheNames = {"user"},
    key = "#userId",
    cacheNull = false  // 不缓存 null，启用布隆过滤器防护
)
```

### Q5: 如何监控缓存命中率？

可以通过 Caffeine 的统计功能（需要配置 `recordStats()`）：

```java
@Cacheable(
    cacheNames = {"user"},
    key = "#id",
    evictionPolicy = Cacheable.EvictionPolicy.LFU  // LFU 策略会记录统计
)
```

## 📚 技术架构

### 多级缓存架构

```
┌─────────────┐
│  应用层      │
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│  CacheAspect    │  ← AOP 切面
└──────┬──────────┘
       │
       ▼
┌─────────────────────┐
│ MultiLevelCache     │
│  Manager            │
└──┬──────────────┬───┘
   │              │
   ▼              ▼
┌─────────┐  ┌──────────┐
│ Local   │  │ Remote   │
│ Cache   │  │ Cache    │
│(Caffeine)│  │ (Redis)  │
└─────────┘  └──────────┘
```

### 缓存流程

1. **查询流程**：本地缓存 → 远程缓存 → 数据库
2. **写入流程**：数据库 → 本地缓存 → 远程缓存
3. **过期策略**：本地缓存过期时间 < 远程缓存过期时间

## 🔧 高级特性

### SpEL 表达式支持

框架支持 Spring Expression Language (SpEL)，可以在 Key、条件、过期时间等地方使用：

```java
// 使用 SpEL 表达式
@Cacheable(
    cacheNames = {"user"},
    key = "'user:' + #userId + ':' + #type",
    condition = "#userId > 0 && #type != null",
    spelExpire = "#ttl != null ? #ttl : 3600"
)
```

### 动态过期时间

支持两种方式动态计算过期时间：

1. **SpEL 表达式**：`spelExpire = "#ttl"`
2. **结果字段**：`resultFieldExpire = "expireTime"`

```java
// 方式1：SpEL 表达式
@Cacheable(
    cacheNames = {"data"},
    key = "#id",
    spelExpire = "#ttl"
)

// 方式2：结果字段
@Cacheable(
    cacheNames = {"data"},
    key = "#id",
    resultFieldExpire = "expireTime"  // 从结果对象的 expireTime 字段获取
)
```

## 📞 技术支持

如有问题或建议，请提交 Issue 或联系维护团队。

---

**版本**: 1.0.0  
**最后更新**: 2024年



