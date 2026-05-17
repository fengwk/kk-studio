package fun.fengwk.kkstudio.core;

import fun.fengwk.convention4j.springboot.test.starter.redis.EnableEmbeddedRedisServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author fengwk
 */
@EnableEmbeddedRedisServer
@SpringBootApplication
public class CoreTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreTestApplication.class, args);
    }

}
