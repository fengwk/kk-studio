package fun.fengwk.kkstudio.harness.infra.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimePayloadFetcher;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 本地优先的 realtime 总线：同时实现 {@link RealtimeEventSink} 与 {@link RealtimeEventSource}。
 *
 * <p>生产事件先在本地同步投递一次，再按 Thread 查询其它节点是否存在有效订阅；只有该 Thread 存在远端订阅时才把事件发布到 PostgreSQL。批量发布先完成整批本地 投递，再按
 * Thread 过滤并以一次批量保持输入相对顺序。每条远端 envelope 都携带 {@code originNodeId}，接收方丢弃自身回环，因此发布节点不会因 LISTEN 收到自己的
 * NOTIFY 而重复投递。远端事件只触达本地订阅，不会再次发布，避免跨节点 echo。
 *
 * <p>PostgreSQL 放不下的 oversize 事件只以 REFERENCE 出现：总线按 origin 从可信内部地址回拉 canonical event，校验其 Thread
 * 后本地投递 一次，绝不再次发布。回拉失败时日志只记录 id，并对目标 Thread 触发 RESYNC；durable 状态与已完成的本地投递不受影响。
 *
 * <p>回拉是阻塞 HTTP，不能占用唯一的 LISTEN 线程：REFERENCE 交给 {@code referenceResolver} 执行，并为该 Thread 开一条有序通道，
 * 回拉完成前到达的同 Thread 通知排在其后依次投递，其它 Thread 不受影响。通道积压超过 {@link #MAX_PENDING_PER_THREAD} 时丢弃积压并以一条
 * RESYNC 代替，由订阅方追帧，内存始终有界。
 *
 * <p>本地投递与远端发布是两个有界步骤：本地订阅回调异常由 {@link PostgresqlRealtimeEventSource} 隔离；远端订阅查询或远端
 * 发布失败在本地投递完成后向上抛出，由 Runtime 既有实时边界隔离，不影响已提交的 durable 状态。
 */
public final class RealtimeBus implements RealtimeEventSink, RealtimeEventSource {

  private static final Logger log = LoggerFactory.getLogger(RealtimeBus.class);

  /** 单个 Thread 在回拉期间可排队的通知上限。 */
  static final int MAX_PENDING_PER_THREAD = 256;

  private final UUID originNodeId;
  private final RealtimeNotificationCodec notificationCodec;
  private final PostgresqlRealtimeEventSource localSource;
  private final RealtimeEventSink remoteSink;
  private final Predicate<UUID> remoteThreadSubscription;
  private final RealtimePayloadFetcher payloadFetcher;
  private final RealtimePayloadBuffer payloadBuffer;
  private final ExecutorService referenceResolver;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 正在回拉 REFERENCE 的 Thread 及其后续排队通知；存在即表示该 Thread 的通道忙。 */
  private final Map<UUID, ArrayDeque<RealtimeNotificationCodec.Envelope>> lanes = new HashMap<>();

  public RealtimeBus(
      UUID originNodeId,
      RealtimeNotificationCodec notificationCodec,
      PostgresqlRealtimeEventSource localSource,
      RealtimeEventSink remoteSink,
      Predicate<UUID> remoteThreadSubscription,
      RealtimePayloadFetcher payloadFetcher,
      RealtimePayloadBuffer payloadBuffer,
      ExecutorService referenceResolver) {
    this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId");
    this.notificationCodec = Objects.requireNonNull(notificationCodec, "notificationCodec");
    this.localSource = Objects.requireNonNull(localSource, "localSource");
    this.remoteSink = Objects.requireNonNull(remoteSink, "remoteSink");
    this.remoteThreadSubscription =
        Objects.requireNonNull(remoteThreadSubscription, "remoteThreadSubscription");
    this.payloadFetcher = Objects.requireNonNull(payloadFetcher, "payloadFetcher");
    this.payloadBuffer = Objects.requireNonNull(payloadBuffer, "payloadBuffer");
    this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    if (closed.get()) {
      return;
    }
    localSource.onEvent(event);
    // 本地回调可能关闭本 bus；关闭后不得再查询远端订阅或向远端发布。
    if (closed.get()) {
      return;
    }
    if (remoteThreadSubscription.test(event.threadId())) {
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
    // 本地回调可能关闭本 bus；关闭后不得再查询远端订阅或向远端批量发布。
    if (closed.get()) {
      return;
    }
    Map<UUID, Boolean> remoteByThread = new HashMap<>();
    List<RealtimeEvent> remoteEvents = new ArrayList<>(events.size());
    for (RealtimeEvent event : events) {
      if (remoteByThread.computeIfAbsent(event.threadId(), remoteThreadSubscription::test)) {
        remoteEvents.add(event);
      }
    }
    if (!remoteEvents.isEmpty()) {
      // 过滤不改变各 Thread 内相对顺序，也不把无远端订阅的 Thread 混入批次。
      remoteSink.appendAll(remoteEvents);
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
    UUID threadId = threadId(envelope);
    synchronized (lanes) {
      ArrayDeque<RealtimeNotificationCodec.Envelope> lane = lanes.get(threadId);
      if (lane != null) {
        enqueue(lane, envelope);
        return;
      }
      if (envelope instanceof RealtimeNotificationCodec.Envelope.Reference) {
        lanes.put(threadId, new ArrayDeque<>(List.of(envelope)));
      }
    }
    if (envelope instanceof RealtimeNotificationCodec.Envelope.Reference) {
      startDrain(threadId);
      return;
    }
    // 只有 LISTEN 线程会新建通道，因此空闲 Thread 的通知可以直接在本线程投递而不破坏顺序。
    localSource.onEnvelope(envelope);
  }

  private void enqueue(
      ArrayDeque<RealtimeNotificationCodec.Envelope> lane,
      RealtimeNotificationCodec.Envelope envelope) {
    if (lane.size() >= MAX_PENDING_PER_THREAD) {
      RealtimeNotificationCodec.Envelope head = lane.peekFirst();
      UUID threadId = threadId(envelope);
      log.warn("realtime reference lane overflow threadId={}; requesting resync", threadId);
      lane.clear();
      // 队首是正在回拉的 REFERENCE，保留它以维持通道语义；积压由一条 RESYNC 代替。
      lane.add(head);
      lane.add(
          new RealtimeNotificationCodec.Envelope.Resync(
              envelope.originNodeId(), threadId, "LANE_OVERFLOW"));
    }
    lane.add(envelope);
  }

  private void startDrain(UUID threadId) {
    try {
      referenceResolver.execute(() -> drain(threadId));
    } catch (RejectedExecutionException rejected) {
      if (!closed.get()) {
        throw rejected;
      }
    }
  }

  /** 依次处理通道：队首 REFERENCE 回拉投递后，按到达顺序投递其后的通知，遇到新的 REFERENCE 同样就地回拉。 */
  private void drain(UUID threadId) {
    while (true) {
      RealtimeNotificationCodec.Envelope next;
      synchronized (lanes) {
        ArrayDeque<RealtimeNotificationCodec.Envelope> lane = lanes.get(threadId);
        next = lane.peekFirst();
      }
      if (closed.get()) {
        return;
      }
      if (next instanceof RealtimeNotificationCodec.Envelope.Reference reference) {
        resolveReference(reference);
      } else {
        localSource.onEnvelope(next);
      }
      synchronized (lanes) {
        ArrayDeque<RealtimeNotificationCodec.Envelope> lane = lanes.get(threadId);
        lane.pollFirst();
        if (lane.isEmpty()) {
          lanes.remove(threadId);
          return;
        }
      }
    }
  }

  private static UUID threadId(RealtimeNotificationCodec.Envelope envelope) {
    return switch (envelope) {
      case RealtimeNotificationCodec.Envelope.Event event -> event.event().threadId();
      case RealtimeNotificationCodec.Envelope.Reference reference -> reference.threadId();
      case RealtimeNotificationCodec.Envelope.Resync resync -> resync.threadId();
    };
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
        referenceResolver.shutdownNow();
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
