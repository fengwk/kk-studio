package fun.fengwk.kkstudio.harness.infra.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimePayloadFetcher;
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
 * <p>PostgreSQL 放不下的 oversize 事件只以 REFERENCE 出现：总线按 origin 从可信内部地址回拉 canonical event，校验其 Thread
 * 后本地投递 一次，绝不再次发布。回拉失败时日志只记录 id，并对目标 Thread 触发 RESYNC；durable 状态与已完成的本地投递不受影响。
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
  private final RealtimePayloadFetcher payloadFetcher;
  private final RealtimePayloadBuffer payloadBuffer;
  private final AtomicBoolean closed = new AtomicBoolean();

  public RealtimeBus(
      UUID originNodeId,
      RealtimeNotificationCodec notificationCodec,
      PostgresqlRealtimeEventSource localSource,
      RealtimeEventSink remoteSink,
      BooleanSupplier otherActiveNodes,
      RealtimePayloadFetcher payloadFetcher,
      RealtimePayloadBuffer payloadBuffer) {
    this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId");
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
    this.localSource = Objects.requireNonNull(localSource, "localSource");
    this.remoteSink = Objects.requireNonNull(remoteSink, "remoteSink");
    this.otherActiveNodes = Objects.requireNonNull(otherActiveNodes, "otherActiveNodes");
    this.payloadFetcher = Objects.requireNonNull(payloadFetcher, "payloadFetcher");
    this.payloadBuffer = Objects.requireNonNull(payloadBuffer, "payloadBuffer");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    if (closed.get()) {
      return;
    }
    localSource.onEvent(event);
    // 本地回调可能关闭本 bus；关闭后不得再查询 presence 或向远端发布。
    if (closed.get()) {
      return;
    }
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
    // 本地回调可能关闭本 bus；关闭后不得再查询 presence 或向远端批量发布。
    if (closed.get()) {
      return;
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
      // 解析异常消息可能包含原始 JSON 片段；只记录异常类别，绝不输出 payload。
      log.warn(
          "cannot decode realtime PostgreSQL notification; requesting resync errorType={}",
          error.getClass().getSimpleName());
      localSource.onResync();
      return;
    }
    if (originNodeId.equals(envelope.originNodeId())) {
      // PostgreSQL 会把 NOTIFY 投递给包括发布者在内的所有监听连接；本节点已在 append 时本地投递。
      return;
    }
    if (envelope instanceof RealtimeNotificationCodec.Envelope.Reference reference) {
      resolveReference(reference);
      return;
    }
    localSource.onEnvelope(envelope);
  }

  /** 从 origin 回拉完整 canonical event；Thread 不符、过期或传输失败都只触发目标 Thread 的 RESYNC。 */
  private void resolveReference(RealtimeNotificationCodec.Envelope.Reference reference) {
    try {
      String canonicalJson =
          payloadFetcher.fetch(
              reference.originNodeId(), reference.threadId(), reference.payloadId());
      // 回拉可能耗时；bus 在等待期间关闭时不得再投递或触发 resync。
      if (closed.get()) {
        return;
      }
      RealtimeEvent event = notificationCodec.decodeEvent(canonicalJson);
      if (closed.get()) {
        return;
      }
      if (!event.threadId().equals(reference.threadId())) {
        throw new IllegalArgumentException("realtime payload thread does not match reference");
      }
      localSource.onEvent(event);
    } catch (RuntimeException error) {
      if (closed.get()) {
        return;
      }
      // 失败日志只含关联 id 与异常类别；不输出 HTTP body、原 JSON 或异常链。
      log.warn(
          "cannot resolve realtime payload payloadId={} originNodeId={} threadId={}; requesting"
              + " resync errorType={}",
          reference.payloadId(),
          reference.originNodeId(),
          reference.threadId(),
          error.getClass().getSimpleName());
      localSource.onEnvelope(
          new RealtimeNotificationCodec.Envelope.Resync(
              reference.originNodeId(), reference.threadId(), "PAYLOAD_UNAVAILABLE"));
    }
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
      try {
        localSource.close();
      } finally {
        payloadBuffer.close();
      }
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
