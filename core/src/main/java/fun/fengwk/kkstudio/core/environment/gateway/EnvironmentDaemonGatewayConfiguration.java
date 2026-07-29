package fun.fengwk.kkstudio.core.environment.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers immutable Environment gateway transport properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnvironmentGatewayProperties.class)
public class EnvironmentDaemonGatewayConfiguration {}
