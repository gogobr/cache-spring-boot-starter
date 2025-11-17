package com.mx.cache.aspect;

import com.mx.cache.annotation.CacheableBatch;
import com.mx.cache.cache.MultiLevelCacheManager;
import com.mx.cache.metadata.CacheAnnotationScanner;
import com.mx.cache.metadata.CacheMethodMetadata;
import com.mx.cache.util.CompressUtils;
import com.mx.cache.util.SerializerUtils;
import com.mx.cache.util.SpelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.*;
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
        if (idList.isEmpty()) {
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

    /**
     * 从方法参数中提取 ID 列表
     *
     * @param args 方法参数
     * @param paramNames 参数名称
     * @param batch 批量缓存注解
     * @return ID 列表
     */
    private List<?> extractIdList(Object[] args, String[] paramNames, CacheableBatch batch) {
        if (batch == null || batch.itemKey() == null || batch.itemKey().isEmpty()) {
            log.warn("Batch cache itemKey is null or empty");
            return Collections.emptyList();
        }

        try {
            Object result = SpelUtils.evaluate(batch.itemKey(), args, paramNames, Object.class);
            if (result instanceof List) {
                return (List<?>) result;
            }
            log.warn("Batch cache id list is not a List, type: {}, method: {}",
                    result != null ? result.getClass().getName() : "null", batch.batchMethod());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to extract id list, itemKey: {}, error: {}", batch.itemKey(), e.getMessage(), e);
            return Collections.emptyList();
        }
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

    /**
     * 从缓存中批量查询已存在的元素
     *
     * @param idToKeyMap ID -> 缓存 key 映射
     * @param batch 批量缓存注解
     * @return ID -> 缓存值映射
     */
    private Map<Object, Object> queryCachedItems(
            Map<Object, String> idToKeyMap, CacheableBatch batch) {
        Map<Object, Object> results = new HashMap<>();
        if (idToKeyMap == null || idToKeyMap.isEmpty()) {
            return results;
        }
        if (batch == null) {
            log.warn("Batch cache annotation is null");
            return results;
        }

        try {
            // 1. 创建 Key -> ID 的反向映射，用于结果组装
            Map<String, Object> keyToIdMap = new HashMap<>();
            for (Map.Entry<Object, String> entry : idToKeyMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    keyToIdMap.put(entry.getValue(), entry.getKey());
                }
            }

            if (keyToIdMap.isEmpty()) {
                return results;
            }

            // 2. 批量获取（使用 Pipeline 优化性能）
            List<String> cacheKeys = new ArrayList<>(keyToIdMap.keySet());
            Map<String, byte[]> cachedDataMap;
            try {
                cachedDataMap = cacheManager.getRemoteCache().pipelineMget(cacheKeys);
            } catch (Exception e) {
                log.error("Batch cache pipeline mget error, keys count: {}, error: {}",
                        cacheKeys.size(), e.getMessage(), e);
                // 降级为普通 mget
                List<byte[]> cachedDataList = cacheManager.getRemoteCache().mget(cacheKeys);
                cachedDataMap = new HashMap<>();
                for (int i = 0; i < cacheKeys.size() && i < cachedDataList.size(); i++) {
                    byte[] data = cachedDataList.get(i);
                    if (data != null) {
                        cachedDataMap.put(cacheKeys.get(i), data);
                    }
                }
            }

            if (cachedDataMap == null || cachedDataMap.isEmpty()) {
                return results;
            }

            // 3. 遍历结果并反序列化
            for (Map.Entry<String, byte[]> entry : cachedDataMap.entrySet()) {
                String key = entry.getKey();
                byte[] data = entry.getValue();
                if (key != null && data != null) {
                    try {
                        Object value = deserialize(data, batch.itemType(), batch);
                        if (value != null) {
                            Object id = keyToIdMap.get(key);
                            if (id != null) {
                                results.put(id, value);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Batch cache deserialize error, key: {}, error: {}", key, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Batch cache query error, idToKeyMap size: {}, error: {}",
                    idToKeyMap.size(), e.getMessage(), e);
        }

        return results;
    }

    /**
     * 查询未缓存的元素
     *
     * @param joinPoint 切点
     * @param method 方法
     * @param missedIds 未缓存的 ID 列表
     * @param batch 批量缓存注解
     * @return ID -> 对象映射
     */
    private Map<Object, Object> queryFreshItems(
            ProceedingJoinPoint joinPoint, Method method, List<Object> missedIds, CacheableBatch batch) {
        if (missedIds == null || missedIds.isEmpty()) {
            return Collections.emptyMap();
        }
        if (batch == null || batch.batchMethod() == null || batch.batchMethod().isEmpty()) {
            log.warn("Batch method is null or empty");
            return Collections.emptyMap();
        }

        try {
            Method batchMethod = findBatchMethod(joinPoint.getTarget().getClass(), batch.batchMethod(), missedIds);
            if (batchMethod == null) {
                log.error("Batch method not found: {}", batch.batchMethod());
                return Collections.emptyMap();
            }

            Object[] batchArgs = new Object[]{missedIds};
            Object result = batchMethod.invoke(joinPoint.getTarget(), batchArgs);
            if (result == null) {
                log.warn("Batch method returned null: {}", batch.batchMethod());
                return Collections.emptyMap();
            }

            if (!(result instanceof List)) {
                log.error("Batch method return type is not List: {}, actual type: {}",
                        batch.batchMethod(), result.getClass().getName());
                return Collections.emptyMap();
            }

            List<?> batchResults = (List<?>) result;
            return batchResults.stream()
                    .filter(item -> item != null)
                    .collect(Collectors.toMap(
                            item -> {
                                try {
                                    return SpelUtils.extractField(item, "id");
                                } catch (Exception e) {
                                    log.error("Failed to extract id field from item: {}, error: {}",
                                            item.getClass().getName(), e.getMessage(), e);
                                    return null;
                                }
                            },
                            item -> item,
                            (existing, replacement) -> existing // 如果有重复的 id，保留第一个
                    ));
        } catch (Exception e) {
            log.error("Failed to query fresh items, batchMethod: {}, missedIds count: {}, error: {}",
                    batch.batchMethod(), missedIds.size(), e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 先组装 Map，然后使用 pipeline 批量写入
     */
    private void putFreshItemsToCache(
            Map<Object, Object> freshResults, Map<Object, String> idToKeyMap, CacheableBatch batch) {

        if (freshResults.isEmpty()) {
            return;
        }

        // 1. 组装所有待写入缓存的 Key-Value
        Map<String, byte[]> itemsToCache = new HashMap<>();
        freshResults.forEach((id, value) -> {
            try {
                String key = idToKeyMap.get(id);
                if (key == null) return;

                byte[] data = serializeAndCompress(value, batch);
                if (data != null) {
                    itemsToCache.put(key, data);
                }
            } catch (Exception e) {
                log.error("Batch cache serialize error, id: {}", id, e);
            }
        });

        if (itemsToCache.isEmpty()) {
            return;
        }

        // 2. 一次性批量写入
        try {
            cacheManager.getRemoteCache().pipelinePut(
                    itemsToCache, batch.expire(), batch.expireUnit()
            );
        } catch (Exception e) {
            log.error("Batch cache pipeline put error", e);
        }
    }

    private List<Object> mergeResults(
            List<?> idList, Map<Object, Object> cachedResults, Map<Object, Object> freshResults) {
        return idList.stream()
                .map(id -> cachedResults.getOrDefault(id, freshResults.get(id)))
                .collect(Collectors.toList());
    }

    /**
     * 查找批量查询方法
     *
     * @param targetClass 目标类
     * @param methodName 方法名
     * @param param 参数（用于类型推断）
     * @return 方法对象
     */
    private Method findBatchMethod(Class<?> targetClass, String methodName, List<?> param) {
        if (targetClass == null || methodName == null || methodName.isEmpty()) {
            return null;
        }

        try {
            return targetClass.getMethod(methodName, List.class);
        } catch (NoSuchMethodException e) {
            log.error("Batch method not found: {} in class: {}, error: {}",
                    methodName, targetClass.getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 序列化并压缩对象
     *
     * @param result 待序列化的对象
     * @param batch 批量缓存注解
     * @return 序列化后的字节数组
     */
    private byte[] serializeAndCompress(Object result, CacheableBatch batch) {
        if (result == null) {
            return null;
        }
        if (batch == null) {
            log.warn("Batch annotation is null, cannot serialize");
            return null;
        }

        try {
            byte[] data = SerializerUtils.serialize(result);
            if (data == null) {
                log.warn("Serialization returned null for object: {}", result.getClass().getName());
                return null;
            }

            if (batch.zip() && data.length >= batch.zipThreshold()) {
                try {
                    return CompressUtils.compress(data);
                } catch (Exception e) {
                    log.error("Compression failed, using uncompressed data, error: {}", e.getMessage(), e);
                    return data;
                }
            }
            return data;
        } catch (Exception e) {
            log.error("Serialization failed for object: {}, error: {}", result.getClass().getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 反序列化对象
     *
     * @param data 序列化的字节数组
     * @param type 目标类型
     * @param batch 批量缓存注解
     * @return 反序列化后的对象
     */
    private Object deserialize(byte[] data, Class<?> type, CacheableBatch batch) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (type == null) {
            log.warn("Target type is null, cannot deserialize");
            return null;
        }
        if (batch == null) {
            log.warn("Batch annotation is null, cannot deserialize");
            return null;
        }

        try {
            byte[] uncompressed = batch.zip() ? CompressUtils.decompress(data) : data;
            return SerializerUtils.deserialize(uncompressed, type);
        } catch (Exception e) {
            log.error("Deserialization failed for type: {}, error: {}", type.getName(), e.getMessage(), e);
            return null;
        }
    }
}
