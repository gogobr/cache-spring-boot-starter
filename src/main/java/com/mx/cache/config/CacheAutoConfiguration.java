package com.mx.cache.config;

import com.mx.cache.aspect.BatchCacheAspect;
import com.mx.cache.aspect.CacheAspect;
import com.mx.cache.aspect.CachePreloadAspect;
import com.mx.cache.aspect.CacheRefreshAspect;
import com.mx.cache.cache.MultiLevelCacheManager;
import com.mx.cache.cache.NoOpRemoteCache;
import com.mx.cache.cache.RemoteCache;
import com.mx.cache.metadata.CacheAnnotationScanner;
import com.mx.cache.util.BloomFilterUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@AutoConfiguration
// vvv 核心修改：添加这一行 vvv
@AutoConfigureAfter(name = {
        // 确保在 Redisson 之后运行 (使用字符串避免硬依赖)
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        // 确保在 Spring Boot 默认的 Redis 配置之后运行
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    /**
     * RedisTemplate for cache data (byte[])
     */
    @Bean
    @ConditionalOnMissingBean(name = "cacheRedisTemplate")
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisTemplate<String, byte[]> cacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * RedisTemplate for lock operations (String)
     */
    @Bean
    @ConditionalOnMissingBean(name = {"lockRedisTemplate", "stringRedisTemplate"})
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisTemplate<String, String> lockRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * RemoteCache bean (only when Redis is available)
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(name = "cacheRedisTemplate")
    public RemoteCache remoteCache(RedisTemplate<String, byte[]> cacheRedisTemplate) {
        return new RemoteCache(cacheRedisTemplate);
    }

    /**
     * MultiLevelCacheManager bean
     * 支持无 Redis 环境，使用 NoOpRemoteCache 降级
     */
    @Bean
    @ConditionalOnMissingBean
    public MultiLevelCacheManager multiLevelCacheManager(
            @Autowired(required = false) RemoteCache remoteCache) {
        // 如果没有 Redis，创建一个不可用的 RemoteCache
        RemoteCache cache = remoteCache != null ? remoteCache : createNoOpRemoteCache();
        MultiLevelCacheManager manager = new MultiLevelCacheManager(cache);
        manager.init();
        return manager;
    }

    private RemoteCache createNoOpRemoteCache() {
        // 创建一个不可用的 RemoteCache，这样多级缓存可以只使用本地缓存
        return new NoOpRemoteCache();
    }

    /**
     * CacheAnnotationScanner bean
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheAnnotationScanner cacheAnnotationScanner() {
        return new CacheAnnotationScanner();
    }

    /**
     * BloomFilterUtils bean
     */
    @Bean
    @ConditionalOnMissingBean
    public BloomFilterUtils bloomFilterUtils(CacheProperties properties) {
        BloomFilterUtils utils = new BloomFilterUtils();
        // 可以通过反射或添加setter方法来设置配置
        return utils;
    }

    /**
     * CacheAspect bean
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheAspect cacheAspect(
            CacheAnnotationScanner annotationScanner,
            MultiLevelCacheManager cacheManager,
            @Autowired(required = false) RedisTemplate<String, String> lockRedisTemplate,
            BloomFilterUtils bloomFilterUtils,
            CacheProperties properties) {
        // 如果没有 Redis，lockRedisTemplate 为 null，热点 key 保护功能将不可用
        CacheAspect aspect = new CacheAspect(annotationScanner, cacheManager, lockRedisTemplate, bloomFilterUtils, properties);
        return aspect;
    }

    /**
     * BatchCacheAspect bean
     */
    @Bean
    @ConditionalOnMissingBean
    public BatchCacheAspect batchCacheAspect(
            CacheAnnotationScanner annotationScanner,
            MultiLevelCacheManager cacheManager,
            CacheProperties properties) {
        BatchCacheAspect aspect = new BatchCacheAspect(annotationScanner, cacheManager);
        return aspect;
    }

    /**
     * CachePreloadAspect bean
     */
    @Bean
    @ConditionalOnMissingBean
    public CachePreloadAspect cachePreloadAspect(
            CacheAnnotationScanner annotationScanner,
            @Qualifier("cacheScheduler") ScheduledExecutorService cacheScheduler,
            org.springframework.context.ApplicationContext applicationContext) {
        return new CachePreloadAspect(annotationScanner, cacheScheduler, applicationContext);
    }

    /**
     * CacheRefreshAspect bean
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheRefreshAspect cacheRefreshAspect(
            CacheAnnotationScanner annotationScanner,
            MultiLevelCacheManager cacheManager,
            @Qualifier("cacheScheduler") ScheduledExecutorService cacheScheduler,
            org.springframework.context.ApplicationContext applicationContext) {
        return new CacheRefreshAspect(annotationScanner, cacheManager, cacheScheduler, applicationContext);
    }

    /**
     * ScheduledExecutorService for cache preload and refresh
     */
    @Bean(name = "cacheScheduler")
    @ConditionalOnMissingBean(name = "cacheScheduler")
    public ScheduledExecutorService cacheScheduler(CacheProperties properties) {
        return Executors.newScheduledThreadPool(properties.getSchedulerPoolSize());
    }

    /**
     * ThreadPoolExecutor for cache operations
     */
    @Bean(name = "cacheExecutor")
    @ConditionalOnMissingBean(name = "cacheExecutor")
    public ThreadPoolExecutor cacheExecutor(CacheProperties properties) {
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(properties.getExecutorPoolSize());
    }
}
