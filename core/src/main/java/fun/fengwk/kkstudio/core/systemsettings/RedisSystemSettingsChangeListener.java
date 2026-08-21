package fun.fengwk.kkstudio.core.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription.Message;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统设置跨节点唤醒订阅。
 *
 * <p>复用 Spring Data Redis {@link ReactiveRedisMessageListenerContainer#receiveLater}：Mono 在 Redis
 * 确认 SUBSCRIBE 之后才发出消息流。订阅确认成功（含 {@code retryWhen} 重挂成功）与每条唤醒消息都触发回读。Pub/Sub 有损，断连窗口靠重挂后的订阅确认回读补齐。
 */
@Slf4j
public final class RedisSystemSettingsChangeListener implements AutoCloseable {

  /** 全局唯一唤醒 channel；与 realtime per-thread channel 隔离。 */
  public static final String CHANNEL = "kk-studio:system-settings";

  private static final long UNBOUNDED_RETRIES = Long.MAX_VALUE;

  private final ReactiveRedisMessageListenerContainer container;
  private final LettuceConnectionFactory ownedFactory;
  private final Disposable listen;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean connected = new AtomicBoolean(false);

  /** 持有独占 Lettuce 工厂：不注册为 Spring {@code RedisConnectionFactory} bean，避免抢走自动配置的 command 连接。 */
  static RedisSystemSettingsChangeListener owning(
      LettuceConnectionFactory exclusiveFactory, Duration retryDelay, Runnable refresh) {
    Objects.requireNonNull(exclusiveFactory, "exclusiveFactory");
    return new RedisSystemSettingsChangeListener(
        new ReactiveRedisMessageListenerContainer(exclusiveFactory),
        CHANNEL,
        retryDelay,
        refresh,
        exclusiveFactory);
  }

  /** 测试可注入 listener container（fake/受控 Flux）与独立 channel。 */
  RedisSystemSettingsChangeListener(
      ReactiveRedisMessageListenerContainer container,
      String channel,
      Duration retryDelay,
      Runnable refresh) {
    this(container, channel, retryDelay, refresh, null);
  }

  private RedisSystemSettingsChangeListener(
      ReactiveRedisMessageListenerContainer container,
      String channel,
      Duration retryDelay,
      Runnable refresh,
      LettuceConnectionFactory ownedFactory) {
    this.container = Objects.requireNonNull(container, "container");
    this.ownedFactory = ownedFactory;
    Objects.requireNonNull(refresh, "refresh");
    Duration delay = requirePositive(retryDelay);
    String topic = RedisSystemSettingsChangePublisher.requireChannel(channel);
    this.listen =
        receive(container, topic)
            .doOnNext(
                ignored -> {
                  connected.set(true);
                  refreshSafely(refresh, topic, "subscribed");
                })
            .flatMapMany(flux -> flux)
            .doOnNext(ignored -> refreshSafely(refresh, topic, "notified"))
            .doOnError(error -> onSubscriptionError(topic, error))
            .retryWhen(Retry.fixedDelay(UNBOUNDED_RETRIES, delay).filter(error -> !closed.get()))
            .onErrorComplete(error -> closed.get())
            .subscribe();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    listen.dispose();
    container.destroy();
    if (ownedFactory != null) {
      ownedFactory.destroy();
    }
  }

  private void onSubscriptionError(String topic, Throwable error) {
    if (connected.getAndSet(false)) {
      log.warn("system settings redis subscription lost on channel={}; retrying", topic, error);
    } else {
      log.debug(
          "system settings redis subscription not connected on channel={}; retrying", topic, error);
    }
  }

  private static Mono<Flux<Message<String, String>>> receive(
      ReactiveRedisMessageListenerContainer container, String channel) {
    return container.receiveLater(new ChannelTopic(channel));
  }

  private static void refreshSafely(Runnable refresh, String channel, String reason) {
    try {
      refresh.run();
    } catch (RuntimeException error) {
      log.warn(
          "cannot refresh system settings snapshot after {} on channel={}", reason, channel, error);
    }
  }

  private static Duration requirePositive(Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("retryDelay must be positive");
    }
    return value;
  }
}
