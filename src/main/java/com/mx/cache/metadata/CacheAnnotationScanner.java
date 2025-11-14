package com.mx.cache.metadata;

import com.mx.cache.annotation.CachePreload;
import com.mx.cache.annotation.CacheRefresh;
import com.mx.cache.annotation.Cacheable;
import com.mx.cache.annotation.CacheableBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CacheAnnotationScanner implements BeanPostProcessor {
    private final Map<String, CacheMethodMetadata> metadataCache = new ConcurrentHashMap<>(128);
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<Class<?>, Boolean> scannedClasses = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (scannedClasses.containsKey(targetClass)) {
            return bean;
        }
        scannedClasses.put(targetClass, Boolean.TRUE);

        try {
            for (Method method : targetClass.getDeclaredMethods()) {
                Cacheable cacheable = method.getAnnotation(Cacheable.class);
                CacheableBatch batch = method.getAnnotation(CacheableBatch.class);

                // 只处理带缓存注解的方法
                if (cacheable == null && batch == null) continue;

                String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
                CachePreload preload = method.getAnnotation(CachePreload.class);
                CacheRefresh refresh = method.getAnnotation(CacheRefresh.class);

                String methodKey = generateMethodKey(method);
                metadataCache.putIfAbsent(methodKey,
                        new CacheMethodMetadata(method, cacheable, preload, refresh, batch, paramNames));
            }
        } catch (Exception e) {
            log.error("扫描缓存注解失败，类名：{}", targetClass.getName(), e);
        }
        return bean;
    }

    public CacheMethodMetadata getMetadata(Method method) {
        return method != null ? metadataCache.get(generateMethodKey(method)) : null;
    }

    private String generateMethodKey(Method method) {
        StringBuilder key = new StringBuilder(method.getDeclaringClass().getName())
                .append("#").append(method.getName()).append("(");
        for (Class<?> paramType : method.getParameterTypes()) {
            key.append(paramType.getCanonicalName()).append(",");
        }
        if (key.charAt(key.length() - 1) == ',') {
            key.deleteCharAt(key.length() - 1);
        }
        key.append(")");
        return key.toString();
    }
}
