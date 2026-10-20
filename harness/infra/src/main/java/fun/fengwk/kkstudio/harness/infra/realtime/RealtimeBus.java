package fun.fengwk.kkstudio.harness.infra.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 本地优先的 realtime 总线：同时实现 {@link RealtimeEventSink} 与 {@link RealtimeEventSource}。
 *
 * <p>生产事件先在本地同步投递一次，再查询节点存在快照；只有存在其它活跃 {@code cluster_node} 时才把同一事件发布到 PostgreSQL。每条远端 envelope 都携带
 * {@code originNodeId}，接收方丢弃自身回环，因此发布节点不会因 LISTEN 收到自己 的 NOTIFY 而重复投递。远端事件只触达本地订阅，不会再次发布，避免跨节点
 * echo。
 *
 * <p>本地投递与远端发布是两个有界步骤：本地订阅回调异常由 {@link PostgresqlRealtimeEventSource} 隔离；节点存在查询或远端
 * 发布失败在本地投递完成后向上抛出，由 Runtime 既有实时边界隔离，不影响已提交的 durable 状态。
 */
public final class RealtimeBus implements RealtimeEventSink, RealtimeEventSource {

  private static final Logger log = LoggerFactory.getLogger(RealtimeBus.class);

  private final UUID originNodeId;
  private final RealtimeNotificationCodec notificationCodec;
  private final PostgresqlRealtimeEventSource localSource;
  private final RealtimeEventSink remoteSink;
  private final BooleanSupplier otherActiveNodes;
  private final AtomicBoolean closed = new AtomicBoolean();

  public RealtimeBus(
      UUID originNodeId,
      RealtimeNotificationCodec notificationCodec,
      PostgresqlRealtimeEventSource localSource,
      RealtimeEventSink remoteSink,
      BooleanSupplier otherActiveNodes) {
    this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId");
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
    this.localSource = Objects.requireNonNull(localSource, "localSource");
    this.remoteSink = Objects.requireNonNull(remoteSink, "remoteSink");
    this.otherActiveNodes = Objects.requireNonNull(otherActiveNodes, "otherActiveNodes");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    if (closed.get()) {
      return;
    }
    localSource.onEvent(event);
    if (otherActiveNodes.getAsBoolean()) {
      remoteSink.append(event);
    }
  }

  @Override
  public void appendAll(List<RealtimeEvent> events) {
    Objects.requireNonNull(events, "events");
    if (closed.get() || events.isEmpty()) {
      return;
    }
    for (RealtimeEvent event : events) {
      localSource.onEvent(Objects.requireNonNull(event, "event"));
    }
    if (otherActiveNodes.getAsBoolean()) {
      remoteSink.appendAll(events);
    }
  }

  @Override
  public AutoCloseable subscribe(
      UUID threadId, Consumer<RealtimeEvent> onEvent, Runnable onResync) {
    return localSource.subscribe(threadId, onEvent, onResync);
  }

  /** 由专用 realtime LISTEN loop 交付原始 notification。 */
  public void onNotification(String payload) {
    if (closed.get()) {
      return;
    }
    RealtimeNotificationCodec.Envelope envelope;
    try {
      envelope = notificationCodec.decode(payload);
    } catch (RuntimeException error) {
      log.warn("cannot decode realtime PostgreSQL notification; requesting resync", error);
      localSource.onResync();
      return;
    }
    if (originNodeId.equals(envelope.originNodeId())) {
      // PostgreSQL 会把 NOTIFY 投递给包括发布者在内的所有监听连接；本节点已在 append 时本地投递。
      return;
    }
    localSource.onEnvelope(envelope);
  }

  /** 专用 realtime LISTEN loop 建连或重连后恢复全部本地订阅。 */
  public void onResync() {
    if (!closed.get()) {
      localSource.onResync();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      localSource.close();
    }
  }

  /**
   * 本节点实例身份，与 {@code cluster_node.instance_id}、{@link PostgresqlRealtimeEventSink} 的 envelope
   * origin 一致。
   */
  public UUID originNodeId() {
    return originNodeId;
  }
}
