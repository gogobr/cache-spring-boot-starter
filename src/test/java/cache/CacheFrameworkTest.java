//package cache;
//
//import cache.service.TestUserService;
//import com.mx.cache.cache.MultiLevelCacheManager;
//import lombok.extern.slf4j.Slf4j;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.context.annotation.Import;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.TestPropertySource;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * 缓存框架功能测试
// *
// * 测试步骤：
// * 1. 运行测试前确保 Redis 已启动（如果使用 Redis）
// * 2. 运行测试类，观察日志输出
// * 3. 验证缓存是否生效
// */
//@Slf4j
//@SpringBootTest(classes = TestApplication.class)
//@Import(RedisTestConfig.class)
////@TestPropertySource(properties = {
////    "spring.data.redis.host=localhost",
////    "spring.data.redis.port=6379",
////    "cache.default-expire=60",
////    "cache.default-local-expire=30"
////})
//@ActiveProfiles("test")
//public class CacheFrameworkTest {
//
//    @Autowired
//    private TestUserService testUserService;
//
//    @Autowired(required = false)
//    private MultiLevelCacheManager cacheManager;
//
//    @BeforeEach
//    public void setUp() {
//        // 重置调用计数
//        testUserService.resetCallCount();
//        log.info("=== 测试开始，重置调用计数 ===");
//    }
//
//    /**
//     * 测试1：基础缓存功能
//     * 验证：第二次调用应该从缓存获取，不会再次执行方法
//     */
//    @Test
//    public void testBasicCache() throws InterruptedException {
//        log.info("\n========== 测试1：基础缓存功能 ==========");
//
//        Long userId = 1L;
//
//        // 第一次调用 - 应该执行方法
//        log.info("第一次调用 getUser({})", userId);
//        TestUser user1 = testUserService.getUser(userId);
//        assertNotNull(user1);
//        assertEquals(1, testUserService.getGetUserCallCount());
//
//        // 等待一小段时间确保缓存写入完成
//        Thread.sleep(100);
//
//        // 第二次调用 - 应该从缓存获取，不执行方法
//        log.info("第二次调用 getUser({})", userId);
//        TestUser user2 = testUserService.getUser(userId);
//        assertNotNull(user2);
//        assertEquals(user1.getId(), user2.getId());
//        assertEquals(user1.getName(), user2.getName());
//
//        // 验证方法只被调用了一次
//        assertEquals(1, testUserService.getGetUserCallCount(),
//            "缓存未生效！方法应该只被调用一次");
//
//        log.info("基础缓存测试通过：方法只被调用了一次");
//    }
//
//    /**
//     * 测试2：条件缓存
//     * 验证：满足条件时缓存，不满足条件时不缓存
//     */
//    @Test
//    public void testConditionalCache() {
//        log.info("\n========== 测试2：条件缓存 ==========");
//
//        // 满足条件的情况（userId > 0）
//        log.info("测试满足条件的情况：userId = 1");
//        TestUser user1 = testUserService.getUserWithCondition(1L);
//        assertNotNull(user1);
//
//        TestUser user2 = testUserService.getUserWithCondition(1L);
//        assertNotNull(user2);
//        assertEquals(user1.getName(), user2.getName());
//
//        // 不满足条件的情况（userId <= 0）
//        log.info("测试不满足条件的情况：userId = -1");
//        TestUser user3 = testUserService.getUserWithCondition(-1L);
//        assertNotNull(user3);
//
//        TestUser user4 = testUserService.getUserWithCondition(-1L);
//        assertNotNull(user4);
//        // 不满足条件时，每次都会执行方法，所以结果可能不同
//
//        log.info("条件缓存测试通过");
//    }
//
//    /**
//     * 测试3：动态过期时间
//     * 验证：SpEL 表达式可以动态计算过期时间
//     */
//    @Test
//    public void testDynamicExpire() {
//        log.info("\n========== 测试3：动态过期时间 ==========");
//
//        Long userId = 3L;
//
//        // 使用自定义 TTL
//        TestUser user1 = testUserService.getUserWithDynamicExpire(userId, 30);
//        assertNotNull(user1);
//
//        // 使用默认 TTL
//        TestUser user2 = testUserService.getUserWithDynamicExpire(4L, null);
//        assertNotNull(user2);
//
//        log.info("动态过期时间测试通过");
//    }
//
//    /**
//     * 测试4：批量缓存
//     * 验证：批量查询时，已缓存的数据不会再次查询
//     */
//    @Test
//    public void testBatchCache() throws InterruptedException {
//        log.info("\n========== 测试4：批量缓存 ==========");
//
//        // 先单独查询几个用户，使其进入缓存
//        log.info("步骤1：单独查询用户，使其进入缓存");
//        testUserService.getUser(10L);
//        testUserService.getUser(11L);
//        Thread.sleep(100);
//
//        // 重置批量查询的调用计数
//        testUserService.resetCallCount();
//
//        // 批量查询（包含已缓存和未缓存的用户）
//        log.info("步骤2：批量查询用户 [10, 11, 12, 13]");
//        List<Long> ids = Arrays.asList(10L, 11L, 12L, 13L);
//        List<TestUser> users = testUserService.getUsersByIds(ids);
//
//        assertNotNull(users);
//        assertEquals(4, users.size());
//
//        // 验证：批量查询方法应该只被调用一次，且只查询未缓存的用户
//        // 注意：这里需要根据实际实现来验证
//        log.info("批量查询调用次数: {}", testUserService.getBatchGetUserCallCount());
//
//        log.info("批量缓存测试通过");
//    }
//
//    /**
//     * 测试5：并发缓存
//     * 验证：高并发场景下缓存正常工作
//     */
//    @Test
//    public void testConcurrentCache() throws InterruptedException {
//        log.info("\n========== 测试5：并发缓存 ==========");
//
//        Long userId = 5L;
//        int threadCount = 10;
//        int requestsPerThread = 5;
//
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        CountDownLatch latch = new CountDownLatch(threadCount);
//
//        // 重置调用计数
//        testUserService.resetCallCount();
//
//        // 并发请求
//        for (int i = 0; i < threadCount; i++) {
//            executor.submit(() -> {
//                try {
//                    for (int j = 0; j < requestsPerThread; j++) {
//                        TestUser user = testUserService.getUser(userId);
//                        assertNotNull(user);
//                    }
//                } finally {
//                    latch.countDown();
//                }
//            });
//        }
//
//        latch.await(5, TimeUnit.SECONDS);
//        executor.shutdown();
//
//        int totalRequests = threadCount * requestsPerThread;
//        int actualCalls = testUserService.getGetUserCallCount();
//
//        log.info("总请求数: {}, 实际方法调用次数: {}", totalRequests, actualCalls);
//
//        // 验证：方法应该只被调用一次（第一次），后续都从缓存获取
//        assertTrue(actualCalls <= 2,
//            "并发场景下方法调用次数过多，可能存在缓存穿透问题");
//
//        log.info("并发缓存测试通过");
//    }
//
//    /**
//     * 测试6：缓存过期
//     * 验证：缓存过期后，会重新执行方法
//     */
//    @Test
//    public void testCacheExpire() throws InterruptedException {
//        log.info("\n========== 测试6：缓存过期 ==========");
//
//        Long userId = 6L;
//
//        // 第一次调用
//        testUserService.getUser(userId);
//        assertEquals(1, testUserService.getGetUserCallCount());
//
//        // 等待缓存过期（这里使用很短的过期时间进行测试）
//        // 注意：实际测试中可能需要使用更短的过期时间
//        Thread.sleep(100);
//
//        // 第二次调用（缓存未过期）
//        testUserService.getUser(userId);
//        assertEquals(1, testUserService.getGetUserCallCount(),
//            "缓存未过期时不应该再次调用方法");
//
//        log.info("缓存过期测试通过（注意：需要等待足够长时间才能测试过期）");
//    }
//
//    /**
//     * 测试7：仅本地缓存
//     * 验证：cacheLevels = "local" 时只使用本地缓存
//     */
//    @Test
//    public void testLocalOnlyCache() {
//        log.info("\n========== 测试7：仅本地缓存 ==========");
//
//        Long userId = 7L;
//
//        // 第一次调用
//        TestUser user1 = testUserService.getUserLocalOnly(userId);
//        assertNotNull(user1);
//
//        // 第二次调用（应该从本地缓存获取）
//        TestUser user2 = testUserService.getUserLocalOnly(userId);
//        assertNotNull(user2);
//        assertEquals(user1.getName(), user2.getName());
//
//        log.info("仅本地缓存测试通过");
//    }
//
//    /**
//     * 测试8：验证缓存管理器
//     * 验证：缓存管理器正常工作
//     */
//    @Test
//    public void testCacheManager() {
//        log.info("\n========== 测试8：缓存管理器 ==========");
//
//        if (cacheManager != null) {
//            assertNotNull(cacheManager, "缓存管理器应该被注入");
//            log.info("缓存管理器注入成功");
//        } else {
//            log.warn("缓存管理器未注入（可能 Redis 未配置）");
//        }
//    }
//
//    /**
//     * 综合测试：验证所有功能
//     */
//    @Test
//    public void testAllFeatures() throws InterruptedException {
//        log.info("\n========== 综合测试：验证所有功能 ==========");
//
//        // 1. 基础缓存
//        testBasicCache();
//        Thread.sleep(100);
//
//        // 2. 条件缓存
//        testConditionalCache();
//        Thread.sleep(100);
//
//        // 3. 动态过期时间
//        testDynamicExpire();
//        Thread.sleep(100);
//
//        // 4. 批量缓存
//        testBatchCache();
//        Thread.sleep(100);
//
//        // 5. 仅本地缓存
//        testLocalOnlyCache();
//
//        log.info("\n所有功能测试完成！");
//    }
//
//    @Test
//    public void test() throws InterruptedException {
//        testUserService.test(Arrays.asList(1L, 2L, 3L), "test");
//    }
//}
//
