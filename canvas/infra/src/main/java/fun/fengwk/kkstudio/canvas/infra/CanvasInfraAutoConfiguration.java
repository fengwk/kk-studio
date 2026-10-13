package fun.fengwk.kkstudio.canvas.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;

/** Canvas PostgreSQL persistence、codec 与 Function Runtime assembly。 */
@ComponentScan
@EnableConfigurationProperties(CanvasFunctionRuntimeProperties.class)
@Configuration(proxyBeanMethods = false)
public class CanvasInfraAutoConfiguration {}
