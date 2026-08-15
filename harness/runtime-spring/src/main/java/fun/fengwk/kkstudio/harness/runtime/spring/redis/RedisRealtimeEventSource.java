package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveSubscription.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redis Pub/Sub {@link RealtimeEventSource}：单 JVM 一个 {@link
 * ReactiveRedisMessageListenerContainer}，按 Thread 动态订阅/释放 exact channel。
 *
 * <p>每个 Thread 一个 channel（{@link RedisRealtimeConfig#channel}）。首个本地订阅建立 channel Flux，最后一个
 * 释放时取消；重复订阅幂等。channel 消息必须且只能是 canonical realtime event JSON（解码后重编码一致）， malformed 消息对当前订阅触发 resync
 * 并丢弃。
 *
 * <p>连接失联/异常时 channel Flux 报错：若此前曾成功连接，先对受影响本地订阅触发 resync，再以固定 1s 延迟 无限重连（未连接成功过的失败只记 debug，不产生
 * resync 风暴）。{@link #lifecycleFence} 把关闭边界与 channel 发布串在同一把锁上，关闭后不再接受订阅，也不会在 destroy 之后遗留 listen。
 */
public final class RedisRealtimeEventSource implements RealtimeEventSource {

  private static final Logger LOG = LoggerFactory.getLogger(RedisRealtimeEventSource.class);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
  private static final long UNBOUNDED_RETRIES = Long.MAX_VALUE;

  private final ReactiveRedisMessageListenerContainer container;
  private final RedisRealtimeConfig config;
  private final RealtimeEventJsonCodec eventCodec;
  private final Map<UUID, ChannelState> channels = new ConcurrentHashMap<>();

  /**
   * 生命周期围栏：{@link #subscribe} 的「检查 closed + 发布 channel」与 {@link #close()} 的「设立 closed 边界 + 排空
   * map」共用，避免 compute 内读 closed 与 close 观察空 map 之间的窗口。
   */
  private final Object lifecycleFence = new Object();

  private final AtomicBoolean closed = new AtomicBoolean(false);

  public RedisRealtimeEventSource(
      ReactiveRedisConnectionFactory connectionFactory,
      RedisRealtimeConfig config,
      RealtimeEventJsonCodec eventCodec) {
    this(new ReactiveRedisMessageListenerContainer(connectionFactory), config, eventCodec);
  }

  /** 测试可注入 listener container（fake/受控 Flux）。 */
  RedisRealtimeEventSource(
      ReactiveRedisMessageListenerContainer container,
      RedisRealtimeConfig config,
      RealtimeEventJsonCodec eventCodec) {
    this.container = Objects.requireNonNull(container, "container");
    this.config = Objects.requireNonNull(config, "config");
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  @Override
  public AutoCloseable subscribe(
      UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onEvent, "onEvent");
    Objects.requireNonNull(onResync, "onResync");
    while (true) {
      // 围栏内完成「检查 closed + 创建/获取 channel」；随后在 channel 锁内校验未被并发最后释放/source close 淘汰
      // （retired 则重取新状态），再按需建立 listen，杜绝在 detached channel 上重建 listener。
      ChannelState channel;
      synchronized (lifecycleFence) {
        if (closed.get()) {
          throw new IllegalStateException("RedisRealtimeEventSource is already closed");
        }
        channel =
            channels.compute(
                threadId,
                (id, existing) ->
                    existing != null ? existing : new ChannelState(id, config.channel(id)));
      }
      synchronized (channel) {
        if (channel.retired) {
          continue;
        }
        if (channel.subscribers.isEmpty()) {
          // receiveLater 在 Redis 确认订阅完成后才发出消息流，用作"曾成功连接"标志。
          channel.listen =
              container
                  .receiveLater(new ChannelTopic(channel.name))
                  .doOnNext(ignored -> channel.connected.set(true))
                  .flatMapMany(flux -> flux)
                  .doOnError(error -> onChannelError(channel, error))
                  .retryWhen(Retry.fixedDelay(UNBOUNDED_RETRIES, RETRY_DELAY))
                  .subscribe(message -> dispatch(channel, message));
        }
        Subscriber subscriber = new Subscriber(onEvent, onResync);
        channel.subscribers.add(subscriber);
        return () -> release(threadId, channel, subscriber);
      }
    }
  }

  @Override
  public void close() {
    synchronized (lifecycleFence) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      // 围栏内排空：此后 subscribe 不能再发布新 channel；已插入、尚未 retired 的条目在 channel 锁内淘汰。
      while (!channels.isEmpty()) {
        for (ChannelState channel : channels.values()) {
          synchronized (channel) {
            if (channel.retired) {
              continue;
            }
            channel.retired = true;
            channels.remove(channel.threadId, channel);
            disposeListen(channel);
          }
        }
      }
    }
    container.destroy();
  }

  private void dispatch(ChannelState channel, Message<String, String> message) {
    RealtimeEvent event;
    String payload = message.getMessage();
    try {
      event = eventCodec.decode(payload);
      if (!eventCodec.encode(event).equals(payload)) {
        throw new IllegalArgumentException("realtime pub/sub message must use canonical JSON");
      }
    } catch (RuntimeException error) {
      LOG.warn(
          "cannot decode realtime pub/sub message on channel={}; requesting resync",
          channel.name,
          error);
      channel.resync();
      return;
    }
    for (Subscriber subscriber : channel.subscribers) {
      try {
        subscriber.onEvent.accept(event);
      } catch (RuntimeException error) {
        // 单个消费者回调异常只隔离该消费者，不阻断同 channel 其他消费者，也不触发全局 resync。
        LOG.warn(
            "realtime subscriber callback failed on channel={}; skipping", channel.name, error);
      }
    }
  }

  private void onChannelError(ChannelState channel, Throwable error) {
    if (channel.connected.getAndSet(false)) {
      LOG.warn(
          "realtime pub/sub listener lost on channel={}; requesting resync", channel.name, error);
      channel.resync();
    } else {
      LOG.debug("realtime pub/sub listener not connected on channel={}", channel.name, error);
    }
  }

  private void release(UUID threadId, ChannelState channel, Subscriber subscriber) {
    synchronized (channel) {
      if (channel.retired) {
        return; // 并发最后释放/hub close 已清理（幂等）
      }
      channel.subscribers.remove(subscriber);
      if (channel.subscribers.isEmpty()) {
        channel.retired = true;
        // 先移除 map entry 再取消 listen：并发 subscribe 只能取得全新 channel，不会复用正在退役的旧 channel。
        channels.remove(threadId, channel);
        disposeListen(channel);
      }
    }
  }

  private static void disposeListen(ChannelState channel) {
    Disposable listen = channel.listen;
    channel.listen = null;
    if (listen != null) {
      listen.dispose();
    }
  }

  private record Subscriber(Consumer<RealtimeEvent> onEvent, Runnable onResync) {}

  private static final class ChannelState {

    private final UUID threadId;
    private final String name;
    private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile Disposable listen;

    /** 已淘汰（最后释放/hub close）：不再建立 listen，订阅回调一律丢弃；只在 channel 锁内读写。 */
    private boolean retired;

    private ChannelState(UUID threadId, String name) {
      this.threadId = threadId;
      this.name = name;
    }

    private void resync() {
      for (Subscriber subscriber : subscribers) {
        try {
          subscriber.onResync.run();
        } catch (RuntimeException error) {
          LOG.warn("realtime resync callback failed on channel={}; skipping", name, error);
        }
      }
    }
  }
}
