package cache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 测试用的 Spring Boot 应用
 */
@SpringBootApplication
@ComponentScan(basePackages = {"cache.service","com.mx.cache"})
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}



