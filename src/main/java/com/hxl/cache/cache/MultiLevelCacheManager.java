package com.hxl.cache.cache;

import com.hxl.cache.annotation.Cacheable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class MultiLevelCacheManager {
    private final Map<String, LocalCache> localCaches = new HashMap<>();
    private final RemoteCache remoteCache;

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

    public byte[] get(String cacheName, String key, Cacheable cacheable) {
        String[] levels = cacheable.cacheLevels().split(",");

        // 先查本地缓存
        if (levels.length > 0 && "local".equals(levels[0])) {
            LocalCache localCache = getLocalCache(cacheName, cacheable);
            byte[] data = localCache.get(key);
            if (data != null) {
                return data;
            }
        }

        // 再查远程缓存
        if (levels.length > 0 && "remote".equals(levels[0]) ||
                levels.length > 1 && "remote".equals(levels[1])) {

            byte[] data = remoteCache.get(key);
            // 远程命中后同步到本地
            if (data != null && levels.length > 0 && "local".equals(levels[0])) {
                getLocalCache(cacheName, cacheable).put(key, data);
            }
            return data;
        }

        return null;
    }

    public void put(String cacheName, String key, byte[] value, long expire,
                    TimeUnit unit, Cacheable cacheable) {
        String[] levels = cacheable.cacheLevels().split(",");

        // 更新本地缓存
        if (levels.length > 0 && "local".equals(levels[0])) {
            getLocalCache(cacheName, cacheable).put(key, value);
        }

        // 更新远程缓存
        if ((levels.length > 0 && "remote".equals(levels[0])) ||
                (levels.length > 1 && "remote".equals(levels[1]))) {
            remoteCache.put(key, value, expire, unit);
        }
    }

    public void evict(String cacheName, String key, Cacheable cacheable) {
        String[] levels = cacheable.cacheLevels().split(",");

        if (levels.length > 0 && "local".equals(levels[0])) {
            LocalCache localCache = localCaches.get(cacheName);
            if (localCache != null) {
                localCache.evict(key);
            }
        }

        if ((levels.length > 0 && "remote".equals(levels[0])) ||
                (levels.length > 1 && "remote".equals(levels[1]))) {
            remoteCache.evict(key);
        }
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
