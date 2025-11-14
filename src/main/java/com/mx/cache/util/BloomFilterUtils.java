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

    public synchronized void add(String cacheName, String key) {
        BloomFilter<String> filter = bloomFilters.computeIfAbsent(cacheName,
                k -> BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8),
                        EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE));
        filter.put(key);
    }

    public boolean mightContain(String cacheName, String key) {
        BloomFilter<String> filter = bloomFilters.get(cacheName);
        return filter != null && filter.mightContain(key);
    }

    public void clear(String cacheName) {
        bloomFilters.remove(cacheName);
        log.info("Bloom filter cleared for cache: {}", cacheName);
    }
}
