package cache;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

@TestConfiguration
@ActiveProfiles("test")
public class RedisTestConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 从 application-test.yml 读取配置
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName("localhost");  // 将从配置文件读取
        config.setPort(6379);             // 将从配置文件读取
        return new LettuceConnectionFactory(config);
    }
}
