package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifier;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

class EnvironmentRegistryFailureTest {

  /** 租约写事务不可用时保留 false 结果，分别记录操作与环境，不暴露数据库异常正文。 */
  @Test
  void failedDisconnectAndReleaseAreObservable() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenThrow(new DataAccessResourceFailureException("private database detail"));
    EnvironmentRegistry registry =
        new EnvironmentRegistry(
            mock(JdbcClient.class),
            UUID.randomUUID(),
            Clock.systemUTC(),
            transactionManager,
            mock(EnvironmentChangeNotifier.class));
    EnvironmentId environmentId = EnvironmentId.of(UUID.randomUUID());
    UUID leaseToken = UUID.randomUUID();

    Logger logger = (Logger) LoggerFactory.getLogger(EnvironmentRegistry.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertFalse(registry.disconnect(environmentId, leaseToken, Duration.ofSeconds(30)));
      assertFalse(registry.release(environmentId, leaseToken));
      assertEquals(2, logs.list.size());
      List<String> actions = List.of("disconnect", "release");
      for (int i = 0; i < actions.size(); i++) {
        ILoggingEvent event = logs.list.get(i);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains(environmentId.toString()));
        assertTrue(event.getFormattedMessage().contains(actions.get(i)));
        assertTrue(event.getFormattedMessage().contains("DataAccessResourceFailureException"));
        assertFalse(event.getFormattedMessage().contains("private database detail"));
        assertFalse(event.getFormattedMessage().contains(leaseToken.toString()));
        assertNull(event.getThrowableProxy());
      }
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }
}
