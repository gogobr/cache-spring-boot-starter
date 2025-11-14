package com.hxl.cache.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hxl.cache.annotation.Cacheable;

import java.util.concurrent.TimeUnit;

public class LocalCache {
    private final Cache<String, byte[]> cache;
    private final Cacheable.EvictionPolicy evictionPolicy;

    public LocalCache(long expire, TimeUnit unit, Cacheable.EvictionPolicy policy,
                      long maxSize, long maxWeight) {
        this.evictionPolicy = policy;
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .expireAfterWrite(expire, unit);

        // 根据不同策略配置缓存
        switch (policy) {
            case LFU:
                builder.maximumSize(maxSize).recordStats();
                break;
            case FIFO:
                builder.maximumSize(maxSize).executor(Runnable::run);
                break;
            case WEIGHT:
                builder.maximumWeight(maxWeight).weigher((k, v) -> ((byte[])v).length);
                break;
            case LRU:
            default:
                builder.maximumSize(maxSize);
                break;
        }

        this.cache = builder.build();
    }

    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, byte[] value) {
        cache.put(key, value);
    }

    public void evict(String key) {
        cache.invalidate(key);
    }

    public Cache<String, byte[]> getCache() {
        return cache;
    }

    public Cacheable.EvictionPolicy getEvictionPolicy() {
        return evictionPolicy;
    }
    public void clear() {
        cache.invalidateAll();
    }
}
