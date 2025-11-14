package com.hxl.cache.aspect;

import com.hxl.cache.annotation.CacheableBatch;
import com.hxl.cache.cache.MultiLevelCacheManager;
import com.hxl.cache.metadata.CacheAnnotationScanner;
import com.hxl.cache.metadata.CacheMethodMetadata;
import com.hxl.cache.util.CompressUtils;
import com.hxl.cache.util.SerializerUtils;
import com.hxl.cache.util.SpelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class BatchCacheAspect {

    private final CacheAnnotationScanner annotationScanner;
    private final MultiLevelCacheManager cacheManager;

    @Around("@annotation(cacheableBatch)")
    public Object aroundBatch(ProceedingJoinPoint joinPoint, CacheableBatch cacheableBatch) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        CacheMethodMetadata metadata = annotationScanner.getMetadata(method);
        if (metadata == null) {
            return joinPoint.proceed();
        }

        // 1. 获取方法参数和集合参数
        Object[] args = joinPoint.getArgs();
        String[] paramNames = metadata.getParamNames();
        List<?> idList = extractIdList(args, paramNames, cacheableBatch);
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 生成每个元素的缓存key
        Map<Object, String> idToKeyMap = generateIdToKeyMap(
                idList, args, paramNames, method, cacheableBatch);

        // 3. 从缓存查询已存在的元素
        Map<Object, Object> cachedResults = queryCachedItems(idToKeyMap, cacheableBatch);

        // 4. 分离已缓存和未缓存的ID
        List<Object> missedIds = idList.stream()
                .filter(id -> !cachedResults.containsKey(id))
                .collect(Collectors.toList());

        // 5. 未缓存的ID调用批量查询方法
        Map<Object, Object> freshResults = new HashMap<>();
        if (!missedIds.isEmpty()) {
            freshResults = queryFreshItems(joinPoint, method, missedIds, cacheableBatch);

            // 6. 将新查询的结果存入缓存
            putFreshItemsToCache(freshResults, idToKeyMap, cacheableBatch);
        }

        // 7. 合并缓存结果和新查询结果（保持原有序）
        return mergeResults(idList, cachedResults, freshResults);
    }

    private List<?> extractIdList(Object[] args, String[] paramNames, CacheableBatch batch) {
        Object result = SpelUtils.evaluate(batch.itemKey(), args, paramNames, Object.class);
        if (result instanceof List) {
            return (List<?>) result;
        }
        log.warn("Batch cache id list is not a List, method: {}", batch.batchMethod());
        return Collections.emptyList();
    }

    private Map<Object, String> generateIdToKeyMap(
            List<?> idList, Object[] args, String[] paramNames, Method method, CacheableBatch batch) {
        return idList.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> generateItemCacheKey(id, args, paramNames, method, batch)
                ));
    }

    private String generateItemCacheKey(
            Object id, Object[] args, String[] paramNames, Method method, CacheableBatch batch) {
        String key = SpelUtils.evaluate(batch.itemKey(), args, paramNames, String.class);
        return batch.cacheNames()[0] + "::" + key;
    }

    private Map<Object, Object> queryCachedItems(
            Map<Object, String> idToKeyMap, CacheableBatch batch) {
        Map<Object, Object> results = new HashMap<>();

        idToKeyMap.forEach((id, key) -> {
            try {
                byte[] data = cacheManager.getRemoteCache().get(key);
                if (data != null) {
                    Object value = deserialize(data, batch.itemType(), batch);
                    results.put(id, value);
                }
            } catch (Exception e) {
                log.error("Batch cache query error, key: {}", key, e);
            }
        });

        return results;
    }

    private Map<Object, Object> queryFreshItems(
            ProceedingJoinPoint joinPoint, Method method, List<Object> missedIds, CacheableBatch batch) throws Exception {
        Method batchMethod = findBatchMethod(joinPoint.getTarget().getClass(), batch.batchMethod(), missedIds);
        Object[] batchArgs = new Object[]{missedIds};
        List<?> batchResults = (List<?>) batchMethod.invoke(joinPoint.getTarget(), batchArgs);

        return batchResults.stream()
                .collect(Collectors.toMap(
                        item -> SpelUtils.extractField(item, "id"),
                        item -> item
                ));
    }

    private void putFreshItemsToCache(
            Map<Object, Object> freshResults, Map<Object, String> idToKeyMap, CacheableBatch batch) {
        freshResults.forEach((id, value) -> {
            try {
                String key = idToKeyMap.get(id);
                if (key == null) return;

                byte[] data = serializeAndCompress(value, batch);
                if (data != null) {
                    cacheManager.getRemoteCache().put(
                            key, data, batch.expire(), batch.expireUnit()
                    );
                }
            } catch (Exception e) {
                log.error("Batch cache put error, id: {}", id, e);
            }
        });
    }

    private List<Object> mergeResults(
            List<?> idList, Map<Object, Object> cachedResults, Map<Object, Object> freshResults) {
        return idList.stream()
                .map(id -> cachedResults.getOrDefault(id, freshResults.get(id)))
                .collect(Collectors.toList());
    }

    private Method findBatchMethod(Class<?> targetClass, String methodName, List<?> param) throws NoSuchMethodException {
        return targetClass.getMethod(methodName, List.class);
    }

    private byte[] serializeAndCompress(Object result, CacheableBatch batch) {
        byte[] data = SerializerUtils.serialize(result);
        if (data == null) return null;

        if (batch.zip() && data.length >= batch.zipThreshold()) {
            return CompressUtils.compress(data);
        }
        return data;
    }

    private Object deserialize(byte[] data, Class<?> type, CacheableBatch batch) {
        if (data == null) return null;

        byte[] uncompressed = batch.zip() ? CompressUtils.decompress(data) : data;
        return SerializerUtils.deserialize(uncompressed, type);
    }
}
