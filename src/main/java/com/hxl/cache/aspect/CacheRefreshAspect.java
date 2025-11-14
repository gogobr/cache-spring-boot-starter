package com.hxl.cache.aspect;

import com.hxl.cache.annotation.CacheRefresh;
import com.hxl.cache.cache.MultiLevelCacheManager;
import com.hxl.cache.metadata.CacheAnnotationScanner;
import com.hxl.cache.metadata.CacheMethodMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class CacheRefreshAspect {

    private final CacheAnnotationScanner annotationScanner;
    private final MultiLevelCacheManager cacheManager;
    private final ScheduledExecutorService cacheScheduler;
    private final ApplicationContext applicationContext;
    
    // 存储每个方法的刷新任务
    private final Map<String, ScheduledFuture<?>> refreshTasks = new ConcurrentHashMap<>();

    @Around("@annotation(com.hxl.cache.annotation.CacheRefresh)")
    public Object aroundRefresh(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        CacheMethodMetadata metadata = annotationScanner.getMetadata(method);
        if (metadata == null || metadata.getRefresh() == null) {
            return joinPoint.proceed();
        }

        CacheRefresh refresh = metadata.getRefresh();
        String methodKey = generateMethodKey(method);

        // 如果还没有启动刷新任务，则启动
        if (!refreshTasks.containsKey(methodKey)) {
            startRefreshTask(joinPoint, method, refresh, metadata);
        }

        // 正常执行原方法
        return joinPoint.proceed();
    }

    private void startRefreshTask(ProceedingJoinPoint joinPoint, Method method, 
                                  CacheRefresh refresh, CacheMethodMetadata metadata) {
        Runnable refreshTask = () -> {
            try {
                Object targetBean = applicationContext.getBean(joinPoint.getTarget().getClass());
                
                if ("FULL".equals(refresh.mode())) {
                    // 全量刷新：重新执行方法并更新缓存
                    try {
                        method.invoke(targetBean, joinPoint.getArgs());
                        log.info("Cache full refresh completed, method: {}", method.getName());
                    } catch (Exception e) {
                        log.error("Cache full refresh failed, method: {}", method.getName(), e);
                    }
                } else if ("INCREMENTAL".equals(refresh.mode())) {
                    // 增量刷新：需要根据业务逻辑实现
                    log.debug("Incremental refresh mode, method: {}", method.getName());
                    // TODO: 实现增量刷新逻辑，可能需要额外的参数来指定刷新范围
                }
            } catch (Exception e) {
                log.error("Cache refresh task execution failed, method: {}", method.getName(), e);
            }
        };

        long period = refresh.period();
        TimeUnit unit = refresh.periodUnit();
        
        ScheduledFuture<?> future;
        if (refresh.initialRefresh()) {
            // 立即执行一次，然后按周期执行
            cacheScheduler.submit(refreshTask);
            future = cacheScheduler.scheduleAtFixedRate(
                    refreshTask, period, period, unit);
        } else {
            // 延迟执行，然后按周期执行
            future = cacheScheduler.scheduleAtFixedRate(
                    refreshTask, period, period, unit);
        }

        refreshTasks.put(generateMethodKey(method), future);
        log.info("Cache refresh task started, method: {}, period: {} {}, initialRefresh: {}", 
                method.getName(), period, unit, refresh.initialRefresh());
    }

    private String generateMethodKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    public void stopRefreshTask(String methodKey) {
        ScheduledFuture<?> future = refreshTasks.remove(methodKey);
        if (future != null) {
            future.cancel(false);
            log.info("Cache refresh task stopped, method: {}", methodKey);
        }
    }
}
