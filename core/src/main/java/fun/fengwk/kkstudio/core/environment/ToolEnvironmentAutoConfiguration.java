package fun.fengwk.kkstudio.core.environment;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the shared Daemon capabilities codec for the Environment application service. */
@Configuration(proxyBeanMethods = false)
public class ToolEnvironmentAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DaemonToolCapabilitiesCodec daemonToolCapabilitiesCodec() {
    return new DaemonToolCapabilitiesCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock toolEnvironmentClock() {
    return Clock.systemUTC();
  }
}
