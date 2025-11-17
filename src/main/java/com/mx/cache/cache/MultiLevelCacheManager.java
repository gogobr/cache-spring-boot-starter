package com.mx.cache.cache;

import com.mx.cache.annotation.Cacheable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存管理器
 * 优化：
 * 1. 使用 ConcurrentHashMap 保证线程安全
 * 2. 缓存 cacheLevels 分割结果，避免频繁 split
 * 3. 优化缓存层级判断逻辑
 */
@Slf4j
@RequiredArgsConstructor
public class MultiLevelCacheManager {
    /**
     * 优化：使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, LocalCache> localCaches = new ConcurrentHashMap<>();
    
    private final RemoteCache remoteCache;
    
    /**
     * 优化：缓存 cacheLevels 分割结果，避免频繁 split
     * Key: cacheLevels 字符串，Value: 分割后的数组
     */
    private final Map<String, String[]> cacheLevelsCache = new ConcurrentHashMap<>();
    
    /**
     * 缓存层级标志位
     */
    private static final int LOCAL_FLAG = 1;
    private static final int REMOTE_FLAG = 2;
    
    /**
     * 缓存层级标志位缓存
     */
    private final Map<String, Integer> cacheLevelsFlagsCache = new ConcurrentHashMap<>();

    public void init() {
        remoteCache.checkHealth();
        log.info("MultiLevelCacheManager initialized, remote cache available: {}", remoteCache.isAvailable());
    }

    public LocalCache getLocalCache(String cacheName, Cacheable cacheable) {
        return localCaches.computeIfAbsent(cacheName, k ->
                new LocalCache(
                        cacheable.localExpire(),
                        cacheable.localExpireUnit(),
                        cacheable.evictionPolicy(),
                        cacheable.maxSize(),
                        cacheable.maxWeight()
                )
        );
    }

    public RemoteCache getRemoteCache() {
        return remoteCache;
    }

    /**
     * 获取缓存值
     * 优化：使用缓存层级标志位，避免频繁字符串比较
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @param cacheable 缓存注解
     * @return 缓存值
     */
    public byte[] get(String cacheName, String key, Cacheable cacheable) {
        int levels = getCacheLevelsFlags(cacheable.cacheLevels());

        // 先查本地缓存
        if ((levels & LOCAL_FLAG) != 0) {
            LocalCache localCache = getLocalCache(cacheName, cacheable);
            byte[] data = localCache.get(key);
            if (data != null) {
                return data;
            }
        }

        // 再查远程缓存
        if ((levels & REMOTE_FLAG) != 0) {
            byte[] data = remoteCache.get(key);
            // 远程命中后同步到本地
            if (data != null && (levels & LOCAL_FLAG) != 0) {
                getLocalCache(cacheName, cacheable).put(key, data);
            }
            return data;
        }

        return null;
    }

    /**
     * 写入缓存值
     * 优化：使用缓存层级标志位
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @param value 缓存值
     * @param expire 过期时间
     * @param unit 时间单位
     * @param cacheable 缓存注解
     */
    public void put(String cacheName, String key, byte[] value, long expire,
                    TimeUnit unit, Cacheable cacheable) {
        int levels = getCacheLevelsFlags(cacheable.cacheLevels());

        // 更新本地缓存
        if ((levels & LOCAL_FLAG) != 0) {
            getLocalCache(cacheName, cacheable).put(key, value);
        }

        // 更新远程缓存
        if ((levels & REMOTE_FLAG) != 0) {
            remoteCache.put(key, value, expire, unit);
        }
    }

    /**
     * 删除缓存值
     * 优化：使用缓存层级标志位
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @param cacheable 缓存注解
     */
    public void evict(String cacheName, String key, Cacheable cacheable) {
        int levels = getCacheLevelsFlags(cacheable.cacheLevels());

        if ((levels & LOCAL_FLAG) != 0) {
            LocalCache localCache = localCaches.get(cacheName);
            if (localCache != null) {
                localCache.evict(key);
            }
        }

        if ((levels & REMOTE_FLAG) != 0) {
            remoteCache.evict(key);
        }
    }
    
    /**
     * 获取缓存层级标志位
     * 优化：缓存解析结果，避免频繁 split 和字符串比较
     *
     * @param cacheLevels 缓存层级字符串，如 "local,remote"
     * @return 标志位（LOCAL_FLAG | REMOTE_FLAG）
     */
    private int getCacheLevelsFlags(String cacheLevels) {
        return cacheLevelsFlagsCache.computeIfAbsent(cacheLevels, levels -> {
            int flags = 0;
            if (levels.contains("local")) {
                flags |= LOCAL_FLAG;
            }
            if (levels.contains("remote")) {
                flags |= REMOTE_FLAG;
            }
            return flags;
        });
    }
    
    /**
     * 获取缓存层级数组（保留作为备用方法，当前使用标志位优化）
     * 优化：缓存分割结果
     *
     * @param cacheLevels 缓存层级字符串
     * @return 分割后的数组
     */
    @SuppressWarnings("unused")
    private String[] getCacheLevels(String cacheLevels) {
        return cacheLevelsCache.computeIfAbsent(cacheLevels, levels -> levels.split(","));
    }

    public void clearAll() {
        // 清空本地缓存
        localCaches.values().forEach(LocalCache::clear);
        localCaches.clear();

        // 清空远程缓存
        if (remoteCache != null) {
            try {
                remoteCache.clear();
                log.info("✅ 已清空远程缓存");
            } catch (Exception e) {
                log.warn("⚠️ 清空远程缓存时出现异常: {}", e.getMessage());
            }
        }

        log.info("✅ MultiLevelCacheManager 全部缓存已清空");
    }
}
