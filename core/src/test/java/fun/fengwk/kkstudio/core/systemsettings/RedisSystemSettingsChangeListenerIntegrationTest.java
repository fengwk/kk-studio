package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 真实 Redis 上验证：receiveLater 订阅确认后回读；随后 PUBLISH 再回读一次。覆盖跨节点时序窗口。 */
class RedisSystemSettingsChangeListenerIntegrationTest {

  private static final Duration RETRY_DELAY = Duration.ofMillis(20);

  private String channel;
  private StringRedisTemplate template;
  private RedisSystemSettingsChangePublisher publisher;
  private SystemSettingsSnapshot snapshot;
  private AtomicReference<SystemSettings> authoritative;
  private RedisSystemSettingsChangeListener listener;

  @BeforeEach
  void setUp() {
    channel = RedisSystemSettingsChangeListener.CHANNEL + ":it:" + UUID.randomUUID();
    template = SystemSettingsRedisFixture.template();
    publisher = new RedisSystemSettingsChangePublisher(template, channel);
    snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    authoritative = new AtomicReference<>(SystemSettings.DEFAULT);
  }

  @AfterEach
  void tearDown() {
    if (listener != null) {
      listener.close();
    }
  }

  @Test
  void subscribeSuccessAndPublishBothReloadAuthoritativeSettings() throws Exception {
    AtomicInteger refreshes = new AtomicInteger();
    listener =
        new RedisSystemSettingsChangeListener(
            new ReactiveRedisMessageListenerContainer(
                SystemSettingsRedisFixture.connectionFactory()),
            channel,
            RETRY_DELAY,
            () -> {
              snapshot.replace(authoritative.get());
              refreshes.incrementAndGet();
            });

    awaitRefreshCount(refreshes, 1);
    assertEquals(SystemSettings.DEFAULT, snapshot.get(), "subscribe reload must read current DB");

    SystemSettings updated = updatedSettings();
    authoritative.set(updated);
    awaitRefreshCount(refreshes, 2, () -> publisher.publish());
    assertEquals(updated, snapshot.get(), "wake must reload the post-publish authoritative row");
  }

  private static SystemSettings updatedSettings() {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        new SystemSettings.AiRuntime(
            9,
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.compactionKeepRecentTokens(),
            SystemSettings.AiRuntime.DEFAULT.compactionFallbackModel(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentIdleTimeoutMillis(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns()),
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }

  private static void awaitRefreshCount(AtomicInteger refreshes, int expected) throws Exception {
    awaitRefreshCount(refreshes, expected, () -> {});
  }

  /** Pub/Sub 不排队：订阅未落位时消息会丢，因此在超时内重复执行 {@code action} 直到计数到达 expected。 */
  private static void awaitRefreshCount(AtomicInteger refreshes, int expected, Runnable action)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      action.run();
      if (refreshes.get() >= expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("refresh count " + refreshes.get() + " did not reach " + expected);
  }
}
