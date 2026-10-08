package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link PlatformNotifications#ENVIRONMENT_CHANGED} 通知的 PostgreSQL 集成测试基座。
 *
 * <p>通知由 Java 生产写入口在成功写事实的同一事务内发布。观察者通过真实 {@link NotificationBus} 订阅同一 topic，因此「提交后才可见」
 * 「回滚与未提交不可见」是被直接观测的事实；订阅由每个测试关闭，避免残留回调或未消费通知掩盖错误。
 */
public abstract class EnvironmentNotificationTestSupport extends PostgresSpringTestSupport {

  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected JdbcTemplate jdbcTemplate;
  @Autowired protected NotificationBus notificationBus;

  /** 打开一条订阅 {@link PlatformNotifications#ENVIRONMENT_CHANGED} 的观察者；由调用方 try-with-resources 关闭。 */
  protected EnvironmentChannelListener listen() {
    return new EnvironmentChannelListener(notificationBus);
  }

  /** 观察者订阅：只等待确定数量的已提交通知，因此「没有通知」也是被观测的结果。 */
  protected static final class EnvironmentChannelListener implements AutoCloseable {

    private static final long AWAIT_TIMEOUT_MILLIS = 5_000;
    private static final long SILENCE_POLL_MILLIS = 50;

    private final BlockingQueue<UUID> received = new LinkedBlockingQueue<>();
    private final NotificationSubscription subscription;

    private EnvironmentChannelListener(NotificationBus notificationBus) {
      this.subscription =
          notificationBus.subscribe(
              PlatformNotifications.ENVIRONMENT_CHANGED, received::add, () -> {});
    }

    /** 断言恰好收到一条指定 environmentId 的已提交通知。 */
    void assertNotification(UUID expectedEnvironmentId) {
      try {
        UUID first = received.poll(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        List<UUID> payloads = new ArrayList<>();
        payloads.add(first);
        received.drainTo(payloads);
        assertEquals(List.of(expectedEnvironmentId), payloads);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("environment notification collection failed", error);
      }
    }

    /** 断言当前没有任何已提交通知：未提交、回滚与围栏静默都由本方法直接观测。 */
    void assertSilent() {
      try {
        assertNull(received.poll(SILENCE_POLL_MILLIS, TimeUnit.MILLISECONDS));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("environment notification collection failed", error);
      }
    }

    @Override
    public void close() {
      subscription.close();
    }
  }
}
