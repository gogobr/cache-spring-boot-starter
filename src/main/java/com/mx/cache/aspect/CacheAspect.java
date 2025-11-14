package com.mx.cache.aspect;

import com.mx.cache.annotation.Cacheable;
import com.mx.cache.cache.MultiLevelCacheManager;
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
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class CacheAspect {
    private final CacheAnnotationScanner annotationScanner;
    private final MultiLevelCacheManager cacheManager;
    private final RedisTemplate<String, String> redisTemplate;
    private final BloomFilterUtils bloomFilterUtils;
    private final ReentrantLock lock = new ReentrantLock();

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

        // 6. 热点key加锁保护（需要 Redis 支持）
        if (cacheable.hotKey() && redisTemplate != null) {
            String lockKey = "hot_key_lock:" + cacheKey;
            try {
                Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
                if (locked != null && locked) {
                    return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
                } else {
                    // 未获取锁，重试缓存查询
                    Thread.sleep(100);
                    byte[] retryData = cacheManager.get(cacheName, cacheKey, cacheable);
                    return retryData != null ? deserialize(retryData, method.getReturnType(), cacheable) :
                            joinPoint.proceed();
                }
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // 非热点key或没有Redis支持，直接处理
            return processCacheMiss(joinPoint, cacheable, cacheName, cacheKey, method);
        }
    }

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

    private boolean isConditionMet(String condition, Object[] args, String[] paramNames) {
        return condition.isEmpty() || SpelUtils.evaluate(condition, args, paramNames, Boolean.class);
    }

    private String generateCacheKey(Cacheable cacheable, Object[] args, String[] paramNames, Method method) {
        String keySpel = cacheable.key().isEmpty() ? "#root.methodName + '_' + #root.args" : cacheable.key();
        String key = SpelUtils.evaluate(keySpel, args, paramNames, String.class);
        return cacheable.cacheNames()[0] + "::" + key;
    }

    private boolean validateKeySize(String key, Cacheable cacheable) {
        int keySize = key.getBytes().length;
        return keySize <= cacheable.maxKeySize();
    }

    private long calculateExpire(Cacheable cacheable, Object[] args, String[] paramNames, Object result) {
        if (!cacheable.spelExpire().isEmpty()) {
            Long spelExpire = SpelUtils.evaluate(cacheable.spelExpire(), args, paramNames, Long.class);
            return spelExpire != null ? spelExpire : cacheable.expire();
        }

        if (!cacheable.resultFieldExpire().isEmpty()) {
            Object fieldValue = SpelUtils.extractField(result, cacheable.resultFieldExpire());
            if (fieldValue instanceof Long) {
                long timestamp = (Long) fieldValue;
                long expire = (timestamp - System.currentTimeMillis()) / 1000;
                return expire > 0 ? expire : cacheable.expire();
            }
        }

        return cacheable.expire();
    }

    private byte[] serializeAndCompress(Object result, Cacheable cacheable) {
        byte[] data = SerializerUtils.serialize(result);
        if (data == null) return null;

        if (cacheable.zip() && data.length >= cacheable.zipThreshold()) {
            return CompressUtils.compress(data);
        }
        return data;
    }

    private Object deserialize(byte[] data, Class<?> returnType, Cacheable cacheable) {
        if (data == null) return null;

        byte[] uncompressed = cacheable.zip() ? CompressUtils.decompress(data) : data;
        return SerializerUtils.deserialize(uncompressed, returnType);
    }

    private boolean isNullOrEmptyMarker(byte[] data) {
        return data.length == 1 && data[0] == 0x00;
    }

    private byte[] getNullOrEmptyMarker() {
        return new byte[]{0x00};
    }
}
