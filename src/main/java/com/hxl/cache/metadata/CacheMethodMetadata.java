package com.hxl.cache.metadata;

import com.hxl.cache.annotation.CachePreload;
import com.hxl.cache.annotation.CacheRefresh;
import com.hxl.cache.annotation.Cacheable;
import com.hxl.cache.annotation.CacheableBatch;
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

