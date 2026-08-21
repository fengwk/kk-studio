package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveSubscription.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** receiveLater 订阅确认、唤醒消息与重挂成功都必须触发 refresh；refresh 抛错不得打断订阅。 */
class RedisSystemSettingsChangeListenerTest {

  private static final Duration RETRY_DELAY = Duration.ofMillis(1);
  private static final String CHANNEL = "kk-studio:system-settings:unit";

  @Test
  void refreshesWhenSubscriptionIsConfirmed() {
    ReactiveRedisMessageListenerContainer container = mockContainer();
    when(container.receiveLater(any(ChannelTopic.class))).thenReturn(Mono.just(Flux.never()));
    AtomicInteger refreshes = new AtomicInteger();

    try (RedisSystemSettingsChangeListener ignored =
        new RedisSystemSettingsChangeListener(
            container, CHANNEL, RETRY_DELAY, refreshes::incrementAndGet)) {
      assertEquals(1, refreshes.get(), "receiveLater success must refresh");
    }
  }

  @Test
  void refreshesAgainWhenWakeMessageArrives() {
    ReactiveRedisMessageListenerContainer container = mockContainer();
    Sinks.Many<Message<String, String>> messages = Sinks.many().unicast().onBackpressureBuffer();
    when(container.receiveLater(any(ChannelTopic.class))).thenReturn(Mono.just(messages.asFlux()));
    AtomicInteger refreshes = new AtomicInteger();

    try (RedisSystemSettingsChangeListener ignored =
        new RedisSystemSettingsChangeListener(
            container, CHANNEL, RETRY_DELAY, refreshes::incrementAndGet)) {
      assertEquals(1, refreshes.get());
      messages.tryEmitNext(new FakeMessage(CHANNEL, ""));
      assertEquals(2, refreshes.get(), "each wake message must refresh");
    }
  }

  @Test
  void refreshesWhenResubscribeSucceedsAfterDisconnect() throws Exception {
    ReactiveRedisMessageListenerContainer container = mockContainer();
    AtomicInteger receiveLaterCalls = new AtomicInteger();
    Sinks.Many<Message<String, String>> recovered = Sinks.many().unicast().onBackpressureBuffer();
    when(container.receiveLater(any(ChannelTopic.class)))
        .thenAnswer(
            invocation -> {
              if (receiveLaterCalls.incrementAndGet() == 1) {
                return Mono.just(
                    Flux.<Message<String, String>>error(new IllegalStateException("lost")));
              }
              return Mono.just(recovered.asFlux());
            });
    AtomicInteger refreshes = new AtomicInteger();

    try (RedisSystemSettingsChangeListener ignored =
        new RedisSystemSettingsChangeListener(
            container, CHANNEL, RETRY_DELAY, refreshes::incrementAndGet)) {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (refreshes.get() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertTrue(refreshes.get() >= 2, "reconnect subscribe success must refresh again");
    }
  }

  @Test
  void refreshFailureDoesNotBreakSubscription() {
    ReactiveRedisMessageListenerContainer container = mockContainer();
    Sinks.Many<Message<String, String>> messages = Sinks.many().unicast().onBackpressureBuffer();
    when(container.receiveLater(any(ChannelTopic.class))).thenReturn(Mono.just(messages.asFlux()));
    AtomicInteger attempts = new AtomicInteger();

    try (RedisSystemSettingsChangeListener ignored =
        new RedisSystemSettingsChangeListener(
            container,
            CHANNEL,
            RETRY_DELAY,
            () -> {
              int n = attempts.incrementAndGet();
              if (n == 1) {
                throw new IllegalStateException("db briefly unavailable");
              }
            })) {
      messages.tryEmitNext(new FakeMessage(CHANNEL, ""));
      assertEquals(2, attempts.get(), "second wake must still refresh after a failed refresh");
    }
  }

  @Test
  void rejectsNonPositiveRetryDelay() {
    ReactiveRedisMessageListenerContainer container = mockContainer();
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedisSystemSettingsChangeListener(container, CHANNEL, Duration.ZERO, () -> {}));
  }

  private static ReactiveRedisMessageListenerContainer mockContainer() {
    ReactiveRedisMessageListenerContainer container =
        mock(ReactiveRedisMessageListenerContainer.class);
    doNothing().when(container).destroy();
    return container;
  }

  private record FakeMessage(String channel, String message) implements Message<String, String> {

    @Override
    public String getChannel() {
      return channel;
    }

    @Override
    public String getMessage() {
      return message;
    }
  }
}
