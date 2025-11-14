package com.mx.cache.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Cacheable {
    String[] cacheNames();
    String key() default "";
    String condition() default "";

    // 过期时间配置（远程缓存）
    long expire() default 3600;
    TimeUnit expireUnit() default TimeUnit.SECONDS;
    String spelExpire() default "";
    String resultFieldExpire() default "";

    // 本地缓存配置
    long localExpire() default 600;
    TimeUnit localExpireUnit() default TimeUnit.SECONDS;
    String cacheLevels() default "local,remote";

    // 压缩配置
    boolean zip() default false;
    int zipThreshold() default 1024;

    // 淘汰策略
    enum EvictionPolicy {
        LRU, LFU, FIFO, WEIGHT
    }
    EvictionPolicy evictionPolicy() default EvictionPolicy.LRU;
    long maxSize() default 10000;
    long maxWeight() default 10485760; // 10MB

    // 其他配置
    int maxKeySize() default 256;
    boolean rejectLargeKey() default false;
    boolean cacheNull() default true;
    boolean hotKey() default false;
}
