package com.mx.cache.util;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class BloomFilterUtils {
    private final Map<String, BloomFilter<String>> bloomFilters = new ConcurrentHashMap<>();
    private static final int EXPECTED_INSERTIONS = 1000000;
    private static final double FALSE_POSITIVE_RATE = 0.001;

    @PostConstruct
    public void init() {
        log.info("BloomFilterUtils initialized");
    }

    /**
     * 添加 key 到布隆过滤器
     * 优化：Guava BloomFilter 的 put() 方法是线程安全的，无需额外同步
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     */
    public void add(String cacheName, String key) {
        if (cacheName == null || key == null) {
            log.warn("Bloom filter add called with null parameters, cacheName: {}, key: {}", cacheName, key);
            return;
        }

        // computeIfAbsent 是原子的，保证了多线程对同一个 cacheName 获取到的是同一个 filter 实例
        // Guava BloomFilter.put() 本身就是线程安全的，无需额外同步
        BloomFilter<String> filter = bloomFilters.computeIfAbsent(cacheName, this::createNewFilter);
        filter.put(key);
    }

    /**
     * 创建新的布隆过滤器实例
     *
     * @param cacheName 缓存名称
     * @return 布隆过滤器实例
     */
    private BloomFilter<String> createNewFilter(String cacheName) {
        log.info("Creating new Bloom filter for cache: {}, expectedInsertions: {}, fpp: {}",
                cacheName, EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
        return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
    }

    /**
     * 检查 key 是否可能存在
     * Guava BloomFilter.mightContain() 是线程安全的，无需同步
     *
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @return 是否可能存在
     */
    public boolean mightContain(String cacheName, String key) {
        if (cacheName == null || key == null) {
            return false;
        }
        BloomFilter<String> filter = bloomFilters.get(cacheName);
        return filter != null && filter.mightContain(key);
    }

    /**
     * 清除指定缓存的布隆过滤器
     *
     * @param cacheName 缓存名称
     */
    public void clear(String cacheName) {
        if (cacheName == null) {
            log.warn("Bloom filter clear called with null cacheName");
            return;
        }
        BloomFilter<String> removed = bloomFilters.remove(cacheName);
        if (removed != null) {
            log.info("Bloom filter cleared for cache: {}", cacheName);
        } else {
            log.debug("Bloom filter not found for cache: {}", cacheName);
        }
    }
}
