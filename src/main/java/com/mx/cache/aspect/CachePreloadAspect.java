package com.mx.cache.aspect;

import com.mx.cache.annotation.CachePreload;
import com.mx.cache.metadata.CacheAnnotationScanner;
import com.mx.cache.metadata.CacheMethodMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class CachePreloadAspect {

    private final CacheAnnotationScanner annotationScanner;
    private final ScheduledExecutorService cacheScheduler;
    private final ApplicationContext applicationContext;
    
    // 记录已启动的预加载任务，避免重复启动
    private final ConcurrentHashMap<String, Boolean> startedTasks = new ConcurrentHashMap<>();

    @Around("@annotation(com.mx.cache.annotation.CachePreload)")
    public Object aroundPreload(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        CacheMethodMetadata metadata = annotationScanner.getMetadata(method);
        if (metadata == null || metadata.getPreload() == null) {
            return joinPoint.proceed();
        }

        CachePreload preload = metadata.getPreload();
        String methodKey = generateMethodKey(method);

        // 如果还没有启动预加载任务，则启动
        if (startedTasks.putIfAbsent(methodKey, Boolean.TRUE) == null) {
            startPreloadTask(joinPoint, method, preload, metadata);
        }

        // 正常执行原方法
        return joinPoint.proceed();
    }

    private void startPreloadTask(ProceedingJoinPoint joinPoint, Method method, 
                                  CachePreload preload, CacheMethodMetadata metadata) {
        Runnable preloadTask = () -> {
            try {
                Object targetBean = applicationContext.getBean(joinPoint.getTarget().getClass());
                int retryCount = preload.retryCount();
                long retryInterval = preload.retryInterval();
                
                for (int i = 0; i < retryCount; i++) {
                    try {
                        // 调用方法以预热缓存
                        method.invoke(targetBean, joinPoint.getArgs());
                        log.info("Cache preload successful, method: {}", method.getName());
                        break;
                    } catch (Exception e) {
                        log.warn("Cache preload failed, attempt {}/{}, method: {}", 
                                i + 1, retryCount, method.getName(), e);
                        if (i < retryCount - 1) {
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Cache preload task execution failed, method: {}", method.getName(), e);
            }
        };

        // 根据配置决定同步或异步执行
        if (preload.async()) {
            if (preload.delay() > 0) {
                cacheScheduler.schedule(preloadTask, preload.delay(), TimeUnit.MILLISECONDS);
            } else {
                cacheScheduler.submit(preloadTask);
            }
        } else {
            if (preload.delay() > 0) {
                try {
                    Thread.sleep(preload.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            preloadTask.run();
        }
        
        log.info("Cache preload task started, method: {}, async: {}, delay: {}ms", 
                method.getName(), preload.async(), preload.delay());
    }

    private String generateMethodKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
