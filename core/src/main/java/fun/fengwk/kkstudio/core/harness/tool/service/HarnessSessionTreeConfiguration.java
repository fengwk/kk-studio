package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.session.store.PostgresqlHarnessSessionStore;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.SessionIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class HarnessSessionTreeConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public SessionTree harnessSessionTree(
      PostgresqlHarnessSessionStore sessionStore,
      HarnessIdGenerator harnessIdGenerator,
      Clock harnessRunClock) {
    SessionIdGenerator idGenerator = asSessionIdGenerator(harnessIdGenerator);
    return new SessionTree(sessionStore, sessionStore, idGenerator, harnessRunClock);
  }

  private static SessionIdGenerator asSessionIdGenerator(HarnessIdGenerator generator) {
    if (generator instanceof SessionIdGenerator sessionIdGenerator) {
      return sessionIdGenerator;
    }
    return new SessionIdGenerator() {
      @Override
      public long newSessionId() {
        return generator.nextSessionId();
      }

      @Override
      public long newEntryId() {
        return generator.nextEntryId();
      }
    };
  }
}
