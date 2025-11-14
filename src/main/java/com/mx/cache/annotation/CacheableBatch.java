package com.mx.cache.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheableBatch {
    String[] cacheNames();
    String itemKey();
    String batchMethod();
    Class<?> itemType();

    long expire() default 3600;
    TimeUnit expireUnit() default TimeUnit.SECONDS;
    boolean zip() default false;
    int zipThreshold() default 1024;
    int maxKeySize() default 256;
}
