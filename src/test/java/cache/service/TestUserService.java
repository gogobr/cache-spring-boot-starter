package cache.service;

import cache.TestUser;
import com.mx.cache.annotation.Cacheable;
import com.mx.cache.annotation.CacheableBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的 UserService
 * 用于验证缓存功能
 */
@Slf4j
@Service
public class TestUserService {

    // 用于统计方法调用次数
    private final AtomicInteger getUserCallCount = new AtomicInteger(0);
    private final AtomicInteger batchGetUserCallCount = new AtomicInteger(0);

    /**
     * 基础缓存测试
     */
    @Cacheable(
        cacheNames = {"test-user"},
        key = "#userId",
        expire = 60,
        expireUnit = TimeUnit.SECONDS
    )
    public TestUser getUser(Long userId) {
        int count = getUserCallCount.incrementAndGet();
        log.info("=== 执行 getUser 方法，userId: {}, 调用次数: {} ===", userId, count);
        
        // 模拟数据库查询
        try {
            Thread.sleep(100); // 模拟数据库查询延迟
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return new TestUser(userId, "User-" + userId, "user" + userId + "@example.com", null);
    }

    /**
     * 条件缓存测试
     */
    @Cacheable(
        cacheNames = {"test-user"},
        key = "#userId",
        condition = "#userId > 0"
    )
    public TestUser getUserWithCondition(Long userId) {
        log.info("=== 执行 getUserWithCondition 方法，userId: {} ===", userId);
        return new TestUser(userId, "User-" + userId, "user" + userId + "@example.com", null);
    }

    /**
     * 动态过期时间测试
     */
    @Cacheable(
        cacheNames = {"test-user"},
        key = "#userId",
        spelExpire = "#ttl != null ? #ttl : 60"
    )
    public TestUser getUserWithDynamicExpire(Long userId, Integer ttl) {
        log.info("=== 执行 getUserWithDynamicExpire 方法，userId: {}, ttl: {} ===", userId, ttl);
        return new TestUser(userId, "User-" + userId, "user" + userId + "@example.com", null);
    }

    /**
     * 从结果字段获取过期时间
     */
    @Cacheable(
        cacheNames = {"test-user"},
        key = "#userId",
        resultFieldExpire = "expireTime"
    )
    public TestUser getUserWithFieldExpire(Long userId) {
        log.info("=== 执行 getUserWithFieldExpire 方法，userId: {} ===", userId);
        long expireTime = System.currentTimeMillis() / 1000 + 120; // 2分钟后过期
        return new TestUser(userId, "User-" + userId, "user" + userId + "@example.com", expireTime);
    }

    /**
     * 压缩测试
     */
    @Cacheable(
        cacheNames = {"test-user"},
        key = "#userId",
        zip = true,
        zipThreshold = 100
    )
    public String getLargeData(Long userId) {
        log.info("=== 执行 getLargeData 方法，userId: {} ===", userId);
        // 生成一个较大的字符串
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("This is a test data for compression. ");
        }
        return sb.toString();
    }

    /**
     * 仅本地缓存测试
     */
    @Cacheable(
        cacheNames = {"test-user-local"},
        key = "#userId",
        cacheLevels = "local"
    )
    public TestUser getUserLocalOnly(Long userId) {
        log.info("=== 执行 getUserLocalOnly 方法，userId: {} ===", userId);
        return new TestUser(userId, "User-" + userId, "user" + userId + "@example.com", null);
    }

    /**
     * 批量缓存测试
     */
    @CacheableBatch(
        cacheNames = {"test-user"},
        itemKey = "#ids",
        batchMethod = "batchGetUsersByIds",
        itemType = TestUser.class
    )
    public List<TestUser> getUsersByIds(List<Long> ids) {
        log.info("=== 执行 getUsersByIds 方法，ids: {} ===", ids);
        return batchGetUsersByIds(ids);
    }

    /**
     * 批量查询的底层方法
     */
    public List<TestUser> batchGetUsersByIds(List<Long> ids) {
        int count = batchGetUserCallCount.incrementAndGet();
        log.info("=== 执行 batchGetUsersByIds 方法，ids: {}, 调用次数: {} ===", ids, count);
        
        List<TestUser> users = new ArrayList<>();
        for (Long id : ids) {
            users.add(new TestUser(id, "User-" + id, "user" + id + "@example.com", null));
        }
        return users;
    }

    /**
     * 获取调用统计
     */
    public int getGetUserCallCount() {
        return getUserCallCount.get();
    }

    public int getBatchGetUserCallCount() {
        return batchGetUserCallCount.get();
    }

    public void resetCallCount() {
        getUserCallCount.set(0);
        batchGetUserCallCount.set(0);
    }
}



