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
import java.util.concurrent.atomic.AtomicInteger;

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

        // 根据配置决定同步或异步执行
        if (preload.async()) {
            // --- 优化路径：async = true ---
            // 使用非阻塞的、可自我调度的 Runnable

            // 使用 AtomicInteger 来在 Runnable 内部跟踪重试次数
            final AtomicInteger attemptCounter = new AtomicInteger(1);
            final int maxAttempts = preload.retryCount();
            final long retryInterval = preload.retryInterval();

            Runnable nonBlockingPreloadTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        Object targetBean = applicationContext.getBean(joinPoint.getTarget().getClass());
                        // 1. 尝试调用方法
                        method.invoke(targetBean, joinPoint.getArgs());
                        log.info("Cache preload successful, method: {}", method.getName());
                        // 成功，任务结束

                    } catch (Exception e) {
                        int currentAttempt = attemptCounter.get();
                        log.warn("Cache preload failed, attempt {}/{}, method: {}",
                                currentAttempt, maxAttempts, method.getName(), e);

                        if (currentAttempt < maxAttempts) {
                            // 2. 失败，但可重试：
                            // 优化：调度下一次重试，而不是 Thread.sleep
                            attemptCounter.incrementAndGet();
                            try {
                                cacheScheduler.schedule(this, retryInterval, TimeUnit.MILLISECONDS); //
                            } catch (Exception scheduleException) {
                                log.error("Failed to schedule preload retry for method: {}", method.getName(), scheduleException);
                            }
                        } else {
                            // 3. 失败，且无重试次数：
                            log.error("Cache preload failed after {} attempts, method: {}",
                                    maxAttempts, method.getName());
                            // 任务结束
                        }
                    } catch (Throwable t) {
                        log.error("Cache preload task execution failed critically (Throwable), method: {}", method.getName(), t);
                    }
                }
            };

            // 提交非阻塞任务
            if (preload.delay() > 0) {
                cacheScheduler.schedule(nonBlockingPreloadTask, preload.delay(), TimeUnit.MILLISECONDS); //
            } else {
                cacheScheduler.submit(nonBlockingPreloadTask); //
            }

        } else {
            // --- 保持原状：async = false ---
            // 这种情况下，用户期望阻塞当前线程（例如 Spring 启动线程）
            // 这里的 Thread.sleep 是符合预期的，且不会占用 cacheScheduler 线程
            Runnable blockingPreloadTask = () -> {
                try {
                    Object targetBean = applicationContext.getBean(joinPoint.getTarget().getClass());
                    int retryCount = preload.retryCount();

                    for (int i = 0; i < retryCount; i++) {
                        try {
                            // 调用方法以预热缓存
                            method.invoke(targetBean, joinPoint.getArgs());
                            log.info("Cache preload successful, method: {}", method.getName());
                            break; // Success
                        } catch (Exception e) {
                            log.warn("Cache preload failed, attempt {}/{}, method: {}",
                                    i + 1, retryCount, method.getName(), e);
                            if (i < retryCount - 1) {
                                try {
                                    // 阻塞当前线程 (非 scheduler 线程)
                                    Thread.sleep(preload.retryInterval()); //
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

            // 在当前线程执行阻塞任务
            if (preload.delay() > 0) {
                try {
                    Thread.sleep(preload.delay()); //
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            blockingPreloadTask.run();
        }

        log.info("Cache preload task started, method: {}, async: {}, delay: {}ms",
                method.getName(), preload.async(), preload.delay());
    }

    private String generateMethodKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
