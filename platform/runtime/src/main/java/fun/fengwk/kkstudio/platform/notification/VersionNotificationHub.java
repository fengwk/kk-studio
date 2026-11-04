package fun.fengwk.kkstudio.platform.notification;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 通用版本推送：生产方定义 PostgreSQL channel 与 payload 协议，订阅方按资源 key 注册。
 *
 * <p>订阅时先在 fan-out 中注册 consumer，再读取权威当前版本作为 ack cursor：返回 cursor 之后的前进保证送达；读取失败会释放刚建立的注册， 并原样抛出
 * {@link RuntimeException} 或 {@link Error}。合法通知只做轻量定点 fan-out；畸形 payload 与 LISTEN 建连/重连退化为全量
 * resync。
 *
 * <p>本类不持有连接、channel 名或领域事实。channel 的投递与监听、payload 解析、版本事件类型都由生产方提供。
 */
@Slf4j
public final class VersionNotificationHub<E> {

  private final Function<String, Notification> decoder;
  private final Function<Long, E> versionEvent;
  private final Supplier<E> resyncEvent;
  private final SubscriptionFanOut<E> subscribers = new SubscriptionFanOut<>();

  /**
   * @param decoder 生产方的 payload 解析；畸形 payload 返回 {@code null}
   * @param versionEvent 把权威版本映射为生产方的事件类型
   * @param resyncEvent 构造生产方的全量回读事件
   */
  public VersionNotificationHub(
      Function<String, Notification> decoder,
      Function<Long, E> versionEvent,
      Supplier<E> resyncEvent) {
    this.decoder = Objects.requireNonNull(decoder, "decoder");
    this.versionEvent = Objects.requireNonNull(versionEvent, "versionEvent");
    this.resyncEvent = Objects.requireNonNull(resyncEvent, "resyncEvent");
  }

  /**
   * 原子注册一个资源 key 的版本订阅：先注册 consumer，再读取权威当前版本返回 ack cursor。
   *
   * @param currentVersion 读取权威当前版本；资源不存在时应抛出 {@link IllegalArgumentException}
   * @throws IllegalArgumentException 资源不存在或读取结果非法；注册已释放，不遗留订阅
   */
  public Subscription subscribe(
      UUID key, ToLongFunction<UUID> currentVersion, Consumer<E> consumer) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(currentVersion, "currentVersion");
    Objects.requireNonNull(consumer, "consumer");
    Runnable release = subscribers.subscribe(key, consumer);
    try {
      long cursor = currentVersion.applyAsLong(key);
      if (cursor < 0L) {
        throw new IllegalArgumentException("current version must not be negative");
      }
      return new Subscription(cursor, release);
    } catch (RuntimeException | Error error) {
      release.run();
      throw error;
    }
  }

  /** 由生产方的统一 LISTEN 循环交付一条已按自身通道协议解码的通知。 */
  public void onNotification(String payload) {
    Notification notification = decoder.apply(payload);
    if (notification == null) {
      log.warn("malformed version notification payload={}; broadcasting resync", payload);
      broadcastResync();
      return;
    }
    subscribers.publish(notification.key(), versionEvent.apply(notification.version()));
  }

  /** 建连/重连：覆盖断连期间不可恢复的通知。 */
  public void broadcastResync() {
    subscribers.broadcast(resyncEvent.get());
  }

  /** 解码后的通知：资源 key 与提交后的权威版本。 */
  public record Notification(UUID key, long version) {

    public Notification {
      Objects.requireNonNull(key, "key");
      if (version < 0L) {
        throw new IllegalArgumentException("version must not be negative");
      }
    }
  }

  /** 订阅建立瞬间的 ack cursor 与幂等释放动作。 */
  public record Subscription(long cursor, Runnable release) implements AutoCloseable {

    public Subscription {
      if (cursor < 0L) {
        throw new IllegalArgumentException("cursor must not be negative");
      }
      Objects.requireNonNull(release, "release");
    }

    @Override
    public void close() {
      release.run();
    }
  }
}
