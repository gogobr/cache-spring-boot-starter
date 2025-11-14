package com.hxl.cache.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

@Slf4j
public class SpelUtils {

    private static final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 评估SpEL表达式
     */
    public static <T> T evaluate(String expression, Object[] args, String[] paramNames, Class<T> resultType) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }

        try {
            Expression exp = parser.parseExpression(expression);
            StandardEvaluationContext context = new StandardEvaluationContext();

            // 设置参数
            if (args != null && paramNames != null) {
                for (int i = 0; i < args.length && i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            // 设置根对象为参数数组
            context.setRootObject(args);

            return exp.getValue(context, resultType);
        } catch (Exception e) {
            log.error("Failed to evaluate SpEL expression: {}", expression, e);
            return null;
        }
    }

    /**
     * 从对象中提取字段值
     */
    public static Object extractField(Object obj, String fieldName) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        try {
            Field field = ReflectionUtils.findField(obj.getClass(), fieldName);
            if (field == null) {
                log.warn("Field {} not found in class: {}", fieldName, obj.getClass().getName());
                return null;
            }

            ReflectionUtils.makeAccessible(field);
            return ReflectionUtils.getField(field, obj);
        } catch (Exception e) {
            log.error("Failed to extract field {} from object: {}", fieldName, obj, e);
            return null;
        }
    }

    /**
     * 判断表达式是否引用了集合参数
     */
    public static boolean isCollectionParam(String expression, String paramName) {
        if (expression == null || paramName == null) {
            return false;
        }

        // 简单判断：表达式是否包含参数名且可能是集合操作
        return expression.contains(paramName) &&
                (expression.contains(".!") || expression.contains(".stream()") ||
                        expression.contains(".forEach") || expression.contains("[") ||
                        expression.contains("?") || expression.contains("^") || expression.contains("$"));
    }
}