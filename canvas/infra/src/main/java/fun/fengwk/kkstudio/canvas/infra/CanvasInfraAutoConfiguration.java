package fun.fengwk.kkstudio.canvas.infra;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Canvas PostgreSQL persistence and codec adapter assembly. */
@BaseMapperScan
@ComponentScan
@Configuration(proxyBeanMethods = false)
public class CanvasInfraAutoConfiguration {}
