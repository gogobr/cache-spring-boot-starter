package com.mx.cache.metadata;

import com.mx.cache.annotation.CachePreload;
import com.mx.cache.annotation.CacheRefresh;
import com.mx.cache.annotation.Cacheable;
import com.mx.cache.annotation.CacheableBatch;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.reflect.Method;

@Data
@AllArgsConstructor
public class CacheMethodMetadata {
    private Method method;
    private Cacheable cacheable;
    private CachePreload preload;
    private CacheRefresh refresh;
    private CacheableBatch batch;
    private String[] paramNames;
}

