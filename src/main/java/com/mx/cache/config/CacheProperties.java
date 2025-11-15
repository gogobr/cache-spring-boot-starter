package com.mx.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {
    /**
     * 缓存切面优先级
     */
    private Integer aspectOrder = 100;

    /**
     * 批量缓存切面优先级
     */
    private Integer batchAspectOrder = 101;

    /**
     * 缓存调度线程池大小
     */
    private Integer schedulerPoolSize = 5;

    /**
     * 缓存执行线程池大小
     */
    private Integer executorPoolSize = 10;

    /**
     * 预热任务重复执行间隔（毫秒）
     */
    private Long preloadInterval = 86400000L;

    /**
     * 默认过期时间（秒）
     */
    private Long defaultExpire = 3600L;

    /**
     * 默认本地缓存过期时间（秒）
     */
    private Long defaultLocalExpire = 600L;

    /**
     * 默认缓存层级
     */
    private String defaultCacheLevels = "local,remote";

    /**
     * 布隆过滤器配置
     */
    private BloomFilter bloomFilter = new BloomFilter();

    /**
     * 热点 key 保护配置
     */
    private HotKeyProtection hotKeyProtection = new HotKeyProtection();

    @Data
    public static class BloomFilter {
        /**
         * 预期插入数量
         */
        private Long expectedInsertions = 1000000L;

        /**
         * 误判率
         */
        private Double falsePositiveRate = 0.01;

        /**
         * 自动刷新间隔（分钟）
         */
        private Long refreshInterval = 60L;
    }

    @Data
    public static class HotKeyProtection {
        /**
         * 热点 key 锁重试次数
         */
        private Integer retryCount = 10;

        /**
         * 热点 key 锁重试间隔（毫秒）
         */
        private Long retryIntervalMs = 50L;

        /**
         * 热点 key 锁超时时间（秒）
         */
        private Long lockTimeoutSeconds = 5L;
    }
}

