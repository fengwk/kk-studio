package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.harness.runtime.session.SessionYoloResolver;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class HarnessSessionTreeConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public SessionTree harnessSessionTree(
      MysqlHarnessSessionStore sessionStore,
      SnowflakeSessionIdGenerator idGenerator,
      SessionYoloResolver yoloResolver,
      Clock harnessRunClock) {
    return new SessionTree(sessionStore, sessionStore, idGenerator, yoloResolver, harnessRunClock);
  }
}
