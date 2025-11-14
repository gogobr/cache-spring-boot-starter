package com.hxl.cache.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheRefresh {
    String params();
    long period();
    TimeUnit periodUnit();
    boolean initialRefresh() default true;
    String mode() default "FULL"; // FULL/INCREMENTAL
}
