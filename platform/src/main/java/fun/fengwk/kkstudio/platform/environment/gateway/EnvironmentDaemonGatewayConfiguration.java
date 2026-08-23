package fun.fengwk.kkstudio.platform.environment.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册不可变的 Environment gateway transport 属性。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnvironmentGatewayProperties.class)
public class EnvironmentDaemonGatewayConfiguration {}
