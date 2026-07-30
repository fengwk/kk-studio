package fun.fengwk.kkstudio.core.ai.environment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;

import java.time.Clock;

/** Wires shared Environment gateway codecs and clocks. */
@Configuration(proxyBeanMethods = false)
public class EnvironmentAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DaemonToolCapabilitiesCodec daemonToolCapabilitiesCodec() {
    return new DaemonToolCapabilitiesCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock environmentClock() {
    return Clock.systemUTC();
  }
}
