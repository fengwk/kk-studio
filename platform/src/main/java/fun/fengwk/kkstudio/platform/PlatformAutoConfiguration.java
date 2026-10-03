package fun.fengwk.kkstudio.platform;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * @author fengwk
 */
@BaseMapperScan("fun.fengwk.kkstudio.platform")
@ComponentScan(basePackageClasses = PlatformAutoConfiguration.class)
@Configuration
@AutoConfigureBefore(MybatisAutoConfiguration.class)
public class PlatformAutoConfiguration {}
