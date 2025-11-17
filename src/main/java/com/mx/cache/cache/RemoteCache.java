package com.mx.cache.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RemoteCache {
    private final RedisTemplate<String, byte[]> redisTemplate;
    private volatile boolean available = true;

    public void checkHealth() {
        if (redisTemplate == null) {
            available = false;
            return;
        }
        try {
            available = redisTemplate.execute((RedisCallback<Boolean>) connection -> connection.ping() != null);
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            available = false;
        }
    }

    /**
     * 获取单个缓存值
     *
     * @param key 缓存 key
     * @return 缓存值，不存在返回 null
     */
    public byte[] get(String key) {
        if (!available || redisTemplate == null) {
            return null;
        }
        if (key == null) {
            log.warn("Redis get called with null key");
            return null;
        }

        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get error, key: {}, error: {}", key, e.getMessage(), e);
            checkHealth();
            return null;
        }
    }

    /**
     * 批量获取缓存（使用 Redis MGET 命令）
     * 注意：MGET 是原子操作，但性能不如 Pipeline
     * 对于大量 key 的批量查询，建议使用 pipelineMget 方法
     *
     * @param keys 缓存 key 集合
     * @return 缓存值列表，与 keys 顺序对应，不存在的 key 对应 null
     */
    public List<byte[]> mget(Collection<String> keys) {
        if (!available || redisTemplate == null) {
            log.debug("Redis not available or template is null, returning empty list");
            return Collections.emptyList();
        }
        if (keys == null || keys.isEmpty()) {
            log.debug("Keys collection is null or empty");
            return Collections.emptyList();
        }

        try {
            List<byte[]> results = redisTemplate.opsForValue().multiGet(keys);
            if (results == null) {
                log.warn("Redis mget returned null for keys count: {}", keys.size());
                return Collections.emptyList();
            }
            log.debug("Redis mget successful, keys count: {}, results count: {}", keys.size(), results.size());
            return results;
        } catch (Exception e) {
            log.error("Redis mget error, keys count: {}, error: {}", keys.size(), e.getMessage(), e);
            checkHealth();
            // 发生异常时返回空列表，避免影响主流程
            return Collections.emptyList();
        }
    }

    /**
     * 使用 Pipeline 批量获取缓存（性能优化）
     * Pipeline 可以减少网络往返次数，提升批量查询性能
     *
     * @param keys 缓存 key 列表
     * @return key -> value 映射，不存在的 key 不会出现在结果中
     */
    public Map<String, byte[]> pipelineMget(List<String> keys) {
        if (!available || redisTemplate == null) {
            log.debug("Redis not available or template is null, returning empty map");
            return Collections.emptyMap();
        }
        if (keys == null || keys.isEmpty()) {
            log.debug("Keys list is null or empty");
            return Collections.emptyMap();
        }

        try {
            // 使用 SessionCallback 以便使用 RedisTemplate 的序列化器
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                    RedisTemplate<String, byte[]> ops = (RedisTemplate<String, byte[]>) operations;
                    for (String key : keys) {
                        if (key != null) {
                            // 使用 RedisTemplate 的 opsForValue().get() 方法，会自动处理序列化
                            ops.opsForValue().get(key);
                        }
                    }
                    return null;
                }
            });

            // 构建 key -> value 映射
            Map<String, byte[]> resultMap = new HashMap<>();
            for (int i = 0; i < keys.size() && i < results.size(); i++) {
                Object value = results.get(i);
                if (value != null && value instanceof byte[]) {
                    resultMap.put(keys.get(i), (byte[]) value);
                }
            }
            log.debug("Redis pipeline mget successful, keys count: {}, results count: {}", keys.size(), resultMap.size());
            return resultMap;
        } catch (Exception e) {
            log.error("Redis pipeline mget error, keys count: {}, error: {}", keys.size(), e.getMessage(), e);
            checkHealth();
            // 降级为普通 mget
            return fallbackMget(keys);
        }
    }

    /**
     * 降级方案：使用普通 mget
     */
    private Map<String, byte[]> fallbackMget(List<String> keys) {
        Map<String, byte[]> resultMap = new HashMap<>();
        List<byte[]> values = mget(keys);
        for (int i = 0; i < keys.size() && i < values.size(); i++) {
            byte[] value = values.get(i);
            if (value != null) {
                resultMap.put(keys.get(i), value);
            }
        }
        return resultMap;
    }

    /**
     * 写入单个缓存值
     *
     * @param key 缓存 key
     * @param value 缓存值
     * @param expire 过期时间
     * @param unit 时间单位
     */
    public void put(String key, byte[] value, long expire, TimeUnit unit) {
        if (!available || redisTemplate == null) {
            return;
        }
        if (key == null) {
            log.warn("Redis put called with null key");
            return;
        }
        if (value == null) {
            log.debug("Redis put called with null value for key: {}", key);
            return;
        }

        try {
            redisTemplate.opsForValue().set(key, value, expire, unit);
        } catch (Exception e) {
            log.error("Redis put error, key: {}, expire: {} {}, error: {}", key, expire, unit, e.getMessage(), e);
            checkHealth();
        }
    }

    /**
     * 使用 Pipeline 批量写入并设置过期时间（性能优化）
     * Pipeline 可以减少网络往返次数，提升批量写入性能
     *
     * @param items key -> value 映射
     * @param expire 过期时间
     * @param unit 时间单位
     */
    public void pipelinePut(Map<String, byte[]> items, long expire, TimeUnit unit) {
        if (!available || redisTemplate == null) {
            log.debug("Redis not available or template is null, skipping pipeline put");
            return;
        }
        if (items == null || items.isEmpty()) {
            log.debug("Items map is null or empty, skipping pipeline put");
            return;
        }

        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                    RedisTemplate<String, byte[]> ops = (RedisTemplate<String, byte[]>) operations;
                    for (Map.Entry<String, byte[]> entry : items.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            // Pipeline 模式下，命令会被缓存，最后一次性发送
                            ops.opsForValue().set(entry.getKey(), entry.getValue(), expire, unit);
                        }
                    }
                    return null;
                }
            });
            log.debug("Redis pipeline put successful, items count: {}", items.size());
        } catch (Exception e) {
            log.error("Redis pipelinePut error, items count: {}, expire: {} {}, error: {}",
                    items.size(), expire, unit, e.getMessage(), e);
            checkHealth();
            // 降级为单次写入
            fallbackPut(items, expire, unit);
        }
    }

    /**
     * 降级方案：单次写入
     */
    private void fallbackPut(Map<String, byte[]> items, long expire, TimeUnit unit) {
        log.warn("Falling back to single put operations, items count: {}", items.size());
        items.forEach((key, value) -> put(key, value, expire, unit));
    }

    /**
     * 删除缓存
     *
     * @param key 缓存 key
     */
    public void evict(String key) {
        if (!available || redisTemplate == null) {
            return;
        }
        if (key == null) {
            log.warn("Redis evict called with null key");
            return;
        }

        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis evict error, key: {}, error: {}", key, e.getMessage(), e);
            checkHealth();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 清空当前数据库的所有缓存（危险操作，谨慎使用）
     * 注意：这会清空 Redis 当前数据库的所有数据，不仅仅是缓存数据
     */
    public void clear() {
        if (!available || redisTemplate == null) {
            log.warn("Redis not available or template is null, cannot clear cache");
            return;
        }

        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.flushDb();
                return null;
            });
            log.info("Redis cache cleared successfully");
        } catch (Exception e) {
            log.error("Failed to clear Redis cache, error: {}", e.getMessage(), e);
            checkHealth();
        }
    }
}

