package com.mx.cache.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SpelUtils {

    private static final ExpressionParser parser = new SpelExpressionParser();
    private static final Map<String, Expression> expressionCache = new ConcurrentHashMap<>(256);

    /**
     * 评估SpEL表达式 (通用)
     * 移除了不兼容的“智能投影”逻辑，使其成为一个通用的、安全的SpEL求值器。
     *
     * @param expression SpEL 表达式
     * @param args       方法参数
     * @param paramNames 参数名称
     * @param resultType 返回类型
     * @return 表达式计算结果
     */
    public static <T> T evaluate(String expression, Object[] args, String[] paramNames, Class<T> resultType) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }

        try {
            // 从缓存获取或解析表达式
            Expression exp = expressionCache.computeIfAbsent(expression, parser::parseExpression);
            StandardEvaluationContext context = new StandardEvaluationContext();

            // 设置参数
            if (args != null && paramNames != null) {
                for (int i = 0; i < args.length && i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            // 执行通用的 SpEL 求值
            return exp.getValue(context, resultType);
        } catch (Exception e) {
            log.error("Failed to evaluate SpEL expression: {}", expression, e);
            return null;
        }
    }

    /**
     * 智能解析 Key 模板 (实现自动投影)
     * * 这正是您要的“智能 SpEL”方案的核心。
     * 它会自动检测表达式中引用的集合参数，并启用投影模式。
     * 它在内部循环中多次调用 getValue()，实现了我们讨论过的 "SmartSpelEvaluator" 逻辑。
     *
     * @param expression SpEL 表达式模板, e.g., "T(...) + '::' + #ids"
     * @param args       方法参数
     * @param paramNames 参数名称
     * @return Map<Object, String> (Map<ID, GeneratedBaseKey>)
     */
    public static Map<Object, String> evaluateKeyTemplate(String expression, Object[] args, String[] paramNames) {
        if (expression == null || expression.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            Expression exp = expressionCache.computeIfAbsent(expression, parser::parseExpression);
            StandardEvaluationContext context = new StandardEvaluationContext();

            String projectionParamName = null;
            Object projectionParamValue = null;

            // 1. 设置所有参数，并“智能”查找投影目标
            if (args != null && paramNames != null) {
                for (int i = 0; i < args.length; i++) {
                    if (i >= paramNames.length) break;
                    String paramName = paramNames[i];
                    Object arg = args[i];
                    context.setVariable(paramName, arg);

                    // 智能检测逻辑
                    if (projectionParamName == null
                            && (arg instanceof Collection || (arg != null && arg.getClass().isArray()))) {
                        // 表达式中必须包含对该参数的引用 (e.g., "#ids")
                        if (expression.contains("#" + paramName)) {
                            projectionParamName = paramName;
                            projectionParamValue = arg;
                        }
                    }
                }
            }

            // 使用 LinkedHashMap 来保持ID的原始顺序
            Map<Object, String> idToKeyMap = new LinkedHashMap<>();

            if (projectionParamName != null) {
                // 模式 A: “智能投影”模式
                log.debug("Batch cache: SpEL projection mode enabled for param: #{}", projectionParamName);

                // 2. 将集合转为可迭代对象
                Iterable<?> iterable;
                if (projectionParamValue instanceof Collection) {
                    iterable = (Collection<?>) projectionParamValue;
                } else {
                    // 处理数组 (包括原生类型)
                    Object finalProjectionParamValue = projectionParamValue;
                    iterable = () -> new Iterator<Object>() {
                        private int index = 0;
                        private final int length = Array.getLength(finalProjectionParamValue);
                        public boolean hasNext() { return index < length; }
                        public Object next() { return Array.get(finalProjectionParamValue, index++); }
                    };
                }

                // 3. 循环，覆盖变量，求值
                for (Object item : iterable) {
                    if (item == null) continue;
                    // *** 核心技巧 ***
                    // 在循环中，用*单个项*覆盖(Overwrite)上下文中的*整个集合*
                    context.setVariable(projectionParamName, item);
                    // 求值
                    String key = exp.getValue(context, String.class);
                    // 存储 <ID, BaseKey> 映射
                    idToKeyMap.put(item, key);
                }
            } else {
                // 模式 B: “普通”模式 (非投影)
                log.warn("Batch cache expression did not reference any collection parameter, " +
                        "treating as single key: {}. This is likely incorrect for @CacheableBatch.", expression);
                // 这种情况无法映射 ID，返回空 Map
                return Collections.emptyMap();
            }

            return idToKeyMap;

        } catch (Exception e) {
            log.error("Failed to evaluate SpEL key template: {}", expression, e);
            return Collections.emptyMap();
        }
    }


    /**
     * 缓存 Field 对象，避免频繁反射查找
     */
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>(128);

    /**
     * 从对象中提取字段值
     * @param obj      对象
     * @param fieldName 字段名称
     */
    public static Object extractField(Object obj, String fieldName) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        try {
            String cacheKey = obj.getClass().getName() + "#" + fieldName;
            Field field = fieldCache.computeIfAbsent(cacheKey, key -> {
                Field f = ReflectionUtils.findField(obj.getClass(), fieldName);
                if (f != null) {
                    ReflectionUtils.makeAccessible(f);
                }
                return f;
            });
            if (field == null) {
                log.warn("Field {} not found in class: {}", fieldName, obj.getClass().getName());
                return null;
            }
            return ReflectionUtils.getField(field, obj);
        } catch (Exception e) {
            log.error("Failed to extract field {} from object: {}", fieldName, obj, e);
            return null;
        }
    }
}