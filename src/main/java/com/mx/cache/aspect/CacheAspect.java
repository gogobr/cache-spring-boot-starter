package com.mx.cache.aspect;

import com.mx.cache.annotation.Cacheable;
import com.mx.cache.cache.MultiLevelCacheManager;
import com.mx.cache.config.CacheProperties;
import com.mx.cache.metadata.CacheAnnotationScanner;
import com.mx.cache.metadata.CacheMethodMetadata;
import com.mx.cache.util.BloomFilterUtils;
import com.mx.cache.util.CompressUtils;
import com.mx.cache.util.SerializerUtils;
import com.mx.cache.util.SpelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class CacheAspect {
    private final CacheAnnotationScanner annotationScanner;
    private final MultiLevelCacheManager cacheManager;
    private final RedisTemplate<String, String> redisTemplate;
    private final BloomFilterUtils bloomFilterUtils;
    private final CacheProperties properties;

    @Around("@annotation(com.mx.cache.annotation.Cacheable)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        CacheMethodMetadata metadata = annotationScanner.getMetadata(method);
        if (metadata == null || metadata.getCacheable() == null) {
            return joinPoint.proceed();
        }

        Cacheable cacheable = metadata.getCacheable();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = metadata.getParamNames();

        // 1. 检查缓存条件
        if (!isConditionMet(cacheable.condition(), args, paramNames)) {
            return joinPoint.proceed();
        }

        // 2. 生成缓存key
        String cacheKey = generateCacheKey(cacheable, args, paramNames, method);

        // 3. 大key校验
        if (!validateKeySize(cacheKey, cacheable)) {
            if (cacheable.rejectLargeKey()) {
                log.warn("Cache key exceeds max size, key: {}", cacheKey);
                return joinPoint.proceed();
            } else {
                log.warn("Cache key exceeds max size but still proceed, key: {}", cacheKey);
            }
        }

        // 4. 布隆过滤器检查（防止缓存穿透）
        String cacheName = cacheable.cacheNames()[0];
        if (!cacheable.cacheNull() && !bloomFilterUtils.mightContain(cacheName, cacheKey)) {
            log.debug("Bloom filter indicates key not exists, key: {}", cacheKey);
            return null;
        }

        // 5. 多级缓存查询
        byte[] cachedData = cacheManager.get(cacheName, cacheKey, cacheable);
        if (cachedData != null) {
            // 检查是否是空值标记
            if (isNullOrEmptyMarker(cachedData)) {
                return null;
            }
            // 解压+反序列化
            return deserialize(cachedData, method.getReturnType(), cacheable);
        }

        // 热点 key 保护逻辑
        if (cacheable.hotKey() && redisTemplate != null) {
            return handleHotKeyProtection(joinPoint, cacheable, cacheName, cacheKey, method);
        } else {
            // 非热点key或没有Redis支持，直接处理
            return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
        }
    }

    /**
     * 处理热点 key 保护逻辑
     * 使用分布式锁防止缓存击穿，未获取锁的线程会重试等待缓存写入
     *
     * @param joinPoint 切点
     * @param cacheable 缓存注解
     * @param cacheName 缓存名称
     * @param cacheKey 缓存 key
     * @param method 方法
     * @return 缓存结果或 null
     */
    private Object handleHotKeyProtection(ProceedingJoinPoint joinPoint, Cacheable cacheable,
                                         String cacheName, String cacheKey, Method method) throws Throwable {
        if (redisTemplate == null) {
            log.warn("Redis template is null, cannot protect hot key: {}", cacheKey);
            return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
        }

        String lockKey = "hot_key_lock:" + cacheKey;
        Boolean locked = false;
        CacheProperties.HotKeyProtection config = properties.getHotKeyProtection();
        int retryCount = config != null ? config.getRetryCount() : 10;
        long retryIntervalMs = config != null ? config.getRetryIntervalMs() : 50L;
        long lockTimeoutSeconds = config != null ? config.getLockTimeoutSeconds() : 5L;

        try {
            // 尝试获取分布式锁
            locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, "1", lockTimeoutSeconds, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(locked)) {
                // 获取锁成功：执行回源
                log.debug("Hot key lock acquired, proceeding to cache miss, key: {}", cacheKey);
                return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
            } else {
                // 获取锁失败：等待其他线程回源完成
                log.debug("Hot key lock failed, entering retry loop, key: {}", cacheKey);
                return retryCacheQuery(cacheName, cacheKey, cacheable, method, retryCount, retryIntervalMs);
            }
        } catch (Exception e) {
            log.error("Hot key protection error, key: {}, error: {}", cacheKey, e.getMessage(), e);
            // 异常情况下降级为直接回源
            return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
        } finally {
            // 只有获取了锁的线程才能释放锁
            if (Boolean.TRUE.equals(locked)) {
                try {
                    redisTemplate.delete(lockKey);
                    log.debug("Hot key lock released, key: {}", cacheKey);
                } catch (Exception e) {
                    log.error("Failed to release hot key lock, key: {}, error: {}", lockKey, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 重试缓存查询
     * 等待其他线程完成回源并写入缓存
     *
     * @param cacheName 缓存名称
     * @param cacheKey 缓存 key
     * @param cacheable 缓存注解
     * @param method 方法
     * @param retryCount 重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @return 缓存结果或 null
     */
    private Object retryCacheQuery(String cacheName, String cacheKey, Cacheable cacheable,
                                   Method method, int retryCount, long retryIntervalMs) {
        for (int i = 0; i < retryCount; i++) {
            try {
                // 等待回源线程写入缓存
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Hot key retry interrupted, key: {}", cacheKey);
                return null;
            }

            // 重试缓存查询
            byte[] retryData = cacheManager.get(cacheName, cacheKey, cacheable);
            if (retryData != null) {
                log.debug("Hot key retry successful at attempt {}, key: {}", i + 1, cacheKey);
                return deserialize(retryData, method.getReturnType(), cacheable);
            }
        }

        // 重试失败，返回 null（防止缓存击穿）
        log.warn("Hot key retry failed after {} retries, returning null. key: {}", retryCount, cacheKey);
        return null;
    }

    /**
     * 处理缓存未命中情况
     * 执行目标方法，序列化结果并存入缓存
     *
     * @param joinPoint 切点
     * @param cacheable 缓存注解
     * @param cacheName 缓存名称
     * @param cacheKey 缓存 key
     * @param method 方法
     * @return 方法执行结果
     */
    private Object processCacheMiss(ProceedingJoinPoint joinPoint, Cacheable cacheable,
                                    String cacheName, String cacheKey, Method method) throws Throwable {
        // 执行目标方法
        Object result = joinPoint.proceed();

        // 处理空结果
        if (result == null) {
            if (cacheable.cacheNull()) {
                // 存储空值标记
                cacheManager.put(cacheName, cacheKey, getNullOrEmptyMarker(),
                        60, TimeUnit.SECONDS, cacheable);
            }
            return null;
        }

        // 计算过期时间
        long expire = calculateExpire(cacheable, joinPoint.getArgs(),
                annotationScanner.getMetadata(method).getParamNames(), result);

        // 序列化+压缩
        byte[] dataToCache = serializeAndCompress(result, cacheable);
        if (dataToCache != null) {
            // 更新布隆过滤器
            bloomFilterUtils.add(cacheName, cacheKey);
            // 存入多级缓存
            cacheManager.put(cacheName, cacheKey, dataToCache, expire, cacheable.expireUnit(), cacheable);
        }

        return result;
    }

    /**
     * 检查缓存条件是否满足
     *
     * @param condition 条件表达式
     * @param args 方法参数
     * @param paramNames 参数名称
     * @return 是否满足条件
     */
    private boolean isConditionMet(String condition, Object[] args, String[] paramNames) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        try {
            Boolean result = SpelUtils.evaluate(condition, args, paramNames, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.error("Failed to evaluate cache condition: {}, error: {}", condition, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成缓存 key
     *
     * @param cacheable 缓存注解
     * @param args 方法参数
     * @param paramNames 参数名称
     * @param method 方法
     * @return 缓存 key
     */
    private String generateCacheKey(Cacheable cacheable, Object[] args, String[] paramNames, Method method) {
        if (cacheable == null || cacheable.cacheNames() == null || cacheable.cacheNames().length == 0) {
            throw new IllegalArgumentException("Cacheable annotation must have at least one cache name");
        }

        String keySpel = cacheable.key().isEmpty() ? "#root.methodName + '_' + #root.args" : cacheable.key();
        try {
            String key = SpelUtils.evaluate(keySpel, args, paramNames, String.class);
            if (key == null) {
                throw new IllegalArgumentException("Cache key evaluation returned null");
            }
            return cacheable.cacheNames()[0] + "::" + key;
        } catch (Exception e) {
            log.error("Failed to generate cache key, spel: {}, error: {}", keySpel, e.getMessage(), e);
            throw new RuntimeException("Failed to generate cache key", e);
        }
    }

    /**
     * 验证缓存 key 大小
     *
     * @param key 缓存 key
     * @param cacheable 缓存注解
     * @return 是否有效
     */
    private boolean validateKeySize(String key, Cacheable cacheable) {
        if (key == null) {
            return false;
        }
        int keySize = key.getBytes().length;
        return keySize <= cacheable.maxKeySize();
    }

    /**
     * 计算缓存过期时间
     * 支持 SpEL 表达式和结果字段两种方式
     *
     * @param cacheable 缓存注解
     * @param args 方法参数
     * @param paramNames 参数名称
     * @param result 方法结果
     * @return 过期时间（秒）
     */
    private long calculateExpire(Cacheable cacheable, Object[] args, String[] paramNames, Object result) {
        if (cacheable == null) {
            return properties.getDefaultExpire();
        }

        // 优先使用 SpEL 表达式计算过期时间
        if (cacheable.spelExpire() != null && !cacheable.spelExpire().isEmpty()) {
            try {
                Long spelExpire = SpelUtils.evaluate(cacheable.spelExpire(), args, paramNames, Long.class);
                if (spelExpire != null && spelExpire > 0) {
                    return spelExpire;
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate spel expire: {}, using default", cacheable.spelExpire(), e);
            }
        }

        // 使用结果字段计算过期时间
        if (cacheable.resultFieldExpire() != null && !cacheable.resultFieldExpire().isEmpty() && result != null) {
            try {
                Object fieldValue = SpelUtils.extractField(result, cacheable.resultFieldExpire());
                if (fieldValue instanceof Long) {
                    long timestamp = (Long) fieldValue;
                    long expire = (timestamp - System.currentTimeMillis()) / 1000;
                    if (expire > 0) {
                        return expire;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract expire field: {}, using default", cacheable.resultFieldExpire(), e);
            }
        }

        // 使用默认过期时间
        return cacheable.expire() > 0 ? cacheable.expire() : properties.getDefaultExpire();
    }

    /**
     * 序列化并压缩对象
     *
     * @param result 待序列化的对象
     * @param cacheable 缓存注解
     * @return 序列化后的字节数组
     */
    private byte[] serializeAndCompress(Object result, Cacheable cacheable) {
        if (result == null) {
            return null;
        }

        try {
            byte[] data = SerializerUtils.serialize(result);
            if (data == null) {
                log.warn("Serialization returned null for object: {}", result.getClass().getName());
                return null;
            }

            // 根据配置决定是否压缩
            if (cacheable.zip() && data.length >= cacheable.zipThreshold()) {
                try {
                    return CompressUtils.compress(data);
                } catch (Exception e) {
                    log.error("Compression failed, using uncompressed data, error: {}", e.getMessage(), e);
                    return data;
                }
            }
            return data;
        } catch (Exception e) {
            log.error("Serialization failed for object: {}, error: {}", result.getClass().getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 反序列化对象
     *
     * @param data 序列化的字节数组
     * @param returnType 返回类型
     * @param cacheable 缓存注解
     * @return 反序列化后的对象
     */
    private Object deserialize(byte[] data, Class<?> returnType, Cacheable cacheable) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            byte[] uncompressed = cacheable.zip() ? CompressUtils.decompress(data) : data;
            return SerializerUtils.deserialize(uncompressed, returnType);
        } catch (Exception e) {
            log.error("Deserialization failed for type: {}, error: {}", returnType.getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查是否是空值标记
     *
     * @param data 数据
     * @return 是否是空值标记
     */
    private boolean isNullOrEmptyMarker(byte[] data) {
        return data != null && data.length == 1 && data[0] == 0x00;
    }

    /**
     * 获取空值标记
     * 用于标记缓存中的 null 值，防止缓存穿透
     *
     * @return 空值标记字节数组
     */
    private byte[] getNullOrEmptyMarker() {
        return new byte[]{0x00};
    }
}
