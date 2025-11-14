package com.hxl.cache.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

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

    public byte[] get(String key) {
        if (!available || redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get error, key: {}", key, e);
            checkHealth();
            return null;
        }
    }

    public void put(String key, byte[] value, long expire, TimeUnit unit) {
        if (!available || redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(key, value, expire, unit);
        } catch (Exception e) {
            log.error("Redis put error, key: {}", key, e);
            checkHealth();
        }
    }

    public void evict(String key) {
        if (!available || redisTemplate == null) return;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis evict error, key: {}", key, e);
            checkHealth();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void clear() {
        if (!available || redisTemplate == null) return;
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.flushDb();
                return null;
            });
            log.info("✅ 已清空远程 Redis 缓存");
        } catch (Exception e) {
            log.error("⚠️ 清空 Redis 缓存时出现异常", e);
            checkHealth();
        }
    }
}
