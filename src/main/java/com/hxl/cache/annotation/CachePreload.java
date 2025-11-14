package com.hxl.cache.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CachePreload {
    String params();
    long delay() default 0;
    int retryCount() default 1;
    long retryInterval() default 1000;
    String group() default "default";
    boolean async() default true;
}