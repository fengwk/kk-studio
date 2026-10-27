package fun.fengwk.kkstudio.ide.mux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Transport-neutral IDE 数据连接 mux v2 引擎（Server 角色）。
 *
 * <p>职责：严格解码入站帧、为 Server 主动流分配单调连续 streamId、维护每流与整连接两个方向的 credit、把管理帧与 DATA 按优先级和同流顺序交给唯一物理写入者。 物理
 * {@link MuxTransport#write(byte[])} 异步完成；任何一次异常完成立即 fail-stop，排队与等待中的 future 全部异常结束，绝不重试或重放请求。
 *
 * <p>背压：管理帧队列固定 256 帧 / 256 KiB；DATA 等待队列按流 256 KiB credit 成本、整连接 4 MiB credit 成本定界，满时同步抛 {@link
 * MuxBackpressureException}。每个 DATA 必须同时取得流与连接发送 credit 才会被选中；接收端只有在调用方对 {@link
 * MuxReceivedData#consumed()} 后才能归还 credit。流 RESET/关闭时，未消费接收缓冲立即折算为连接级 WINDOW 归还，不保留按流
 * tombstone；已分配但关闭的 streamId 仅由单调上界识别。
 *
 * <p>入站回调在连接状态锁外按 wire 顺序投递。适配器必须在单线程内调用 {@link #receive(byte[])}，并在物理连接断开时调用 {@link
 * #fail(Throwable)} 或 {@link #close()}；WebSocket 原生 Ping/Pong 与 HTTP 超时属于适配器职责。
 */
public final class MuxConnection implements AutoCloseable {

  private static final ScheduledExecutorService DEFAULT_TIMER =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "mux-ws-close-timer");
            thread.setDaemon(true);
            return thread;
          });

  private static final Duration DEFAULT_WS_CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final MuxWireCodec codec = new MuxWireCodec();
  private final MuxTransport transport;
  private final MuxConnectionListener listener;
  private final ScheduledExecutorService timer;
  private final Duration wsCloseTimeout;

  private final Object lock = new Object();
  private final Object pumpGate = new Object();
  private final Map<Long, MuxStream> streamsById = new HashMap<>();
  private final List<MuxStream> streams = new ArrayList<>();
  private final Set<MuxStream> liveStreams = Collections.newSetFromMap(new IdentityHashMap<>());
  private final ArrayDeque<Outbound> management = new ArrayDeque<>();

  private int managementFrames;
  private long managementBytes;
  private long queuedDataCost;
  private long nextStreamId = 1;
  private long maxAllocatedStreamId;
  private long connectionSendUnacked;
  private long connectionReceiveUnacked;
  private int roundRobin;
  private boolean writing;
  private boolean pumping;
  private boolean pumpRequested;
  private boolean stopped;
  private Throwable failure;
  private Outbound inFlight;

  /** 使用共享 close 定时器与 5 秒 WS 关闭握手时限。 */
  public MuxConnection(MuxTransport transport, MuxConnectionListener listener) {
    this(transport, listener, DEFAULT_TIMER, DEFAULT_WS_CLOSE_TIMEOUT);
  }

  /** 注入 close 定时器；便于宿主复用调度器并控制关闭握手时限。 */
  public MuxConnection(
      MuxTransport transport,
      MuxConnectionListener listener,
      ScheduledExecutorService timer,
      Duration wsCloseTimeout) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.timer = Objects.requireNonNull(timer, "timer");
    this.wsCloseTimeout = Objects.requireNonNull(wsCloseTimeout, "wsCloseTimeout");
    if (wsCloseTimeout.isNegative() || wsCloseTimeout.isZero()) {
      throw new IllegalArgumentException("wsCloseTimeout must be positive");
    }
  }

  MuxConnection(MuxTransport transport, MuxConnectionListener listener, Duration wsCloseTimeout) {
    this(transport, listener, DEFAULT_TIMER, wsCloseTimeout);
  }

  /**
   * 打开一条 Server 发起的逻辑流。OPEN 进入管理队列；真正物理发送时才分配连续 streamId。
   *
   * @throws MuxBackpressureException 并发流达到 64 或 streamId 空间耗尽
   * @throws MuxProtocolException 管理帧队列耗尽，并 fail-stop 整条连接
   */
  public MuxStream open(MuxOpen request, MuxStreamListener streamListener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(streamListener, "streamListener");
    MuxStream stream;
    try {
      synchronized (lock) {
        if (stopped) {
          throw closedException("connection is stopped");
        }
        if (streams.size() >= MuxLimits.MAX_STREAMS) {
          throw new MuxBackpressureException(
              "connection already has " + MuxLimits.MAX_STREAMS + " active streams");
        }
        if (nextStreamId > MuxFrame.MAX_STREAM_ID) {
          throw new MuxBackpressureException(
              "stream id space exhausted; rebuild the data connection");
        }
        stream = new MuxStream(this, request, streamListener);
        enqueueManagementLocked(Outbound.open(this, stream, request));
        streams.add(stream);
        liveStreams.add(stream);
      }
    } catch (MuxProtocolException error) {
      failConnection(error);
      throw error;
    }
    pump();
    return stream;
  }

  /**
   * 处理一条完整入站 wire 帧。协议错误会 fail-stop 整条连接后以 {@link MuxProtocolException} 抛出；连接已终止时抛 {@link
   * MuxConnectionClosedException}。
   */
  public void receive(byte[] wireFrame) {
    MuxFrame frame;
    try {
      frame = codec.decode(wireFrame);
    } catch (MuxProtocolException error) {
      failConnection(error);
      throw error;
    }
    List<Runnable> after = new ArrayList<>();
    try {
      synchronized (lock) {
        if (stopped) {
          throw closedException("connection is stopped");
        }
        handleFrameLocked(frame, after);
      }
    } catch (RuntimeException error) {
      runAllQuietly(after);
      failConnection(error);
      throw error;
    }
    try {
      runAll(after);
    } catch (RuntimeException error) {
      failConnection(error);
      throw error;
    }
    pump();
  }

  /** 底层传输失败或适配器检测到连接断开时调用；幂等 fail-stop。 */
  public void fail(Throwable cause) {
    Objects.requireNonNull(cause, "cause");
    failConnection(cause);
  }

  /** 关闭整条连接；不发送任何重放或成功帧。 */
  @Override
  public void close() {
    terminate(new MuxConnectionClosedException("connection closed by caller"), false);
  }

  /** 连接仍接受入站帧与本地操作。 */
  public boolean isOpen() {
    synchronized (lock) {
      return !stopped;
    }
  }

  /** 当前活动流数（不含仅等待 RESET 物理写完成的已关闭流）。 */
  public int activeStreamCount() {
    synchronized (lock) {
      return streams.size();
    }
  }

  /** 首次终止原因；正常关闭也有原因。 */
  public Optional<Throwable> failure() {
    synchronized (lock) {
      return Optional.ofNullable(failure);
    }
  }

  OptionalLong streamIdOf(MuxStream stream) {
    synchronized (lock) {
      return stream.streamId == 0 ? OptionalLong.empty() : OptionalLong.of(stream.streamId);
    }
  }

  boolean isStreamOpen(MuxStream stream) {
    synchronized (lock) {
      return !stopped && stream.active;
    }
  }

  boolean isStreamReady(MuxStream stream) {
    synchronized (lock) {
      return stream.ready;
    }
  }

  CompletableFuture<Void> sendData(
      MuxStream stream, MuxDataKind kind, boolean fin, byte[] payload) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(payload, "payload");
    if (payload.length > MuxLimits.MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
    }
    if (stream.mode() == MuxMode.HTTP && kind != MuxDataKind.HTTP) {
      throw new IllegalArgumentException("HTTP stream only accepts HTTP DATA");
    }
    if (stream.mode() == MuxMode.WS && kind == MuxDataKind.HTTP) {
      throw new IllegalArgumentException("WS stream requires TEXT or BINARY DATA");
    }
    int cost = MuxLimits.dataCost(payload.length);
    CompletableFuture<Void> completion = new CompletableFuture<>();
    synchronized (lock) {
      requireSendableLocked(stream);
      if (stream.outboundEnded || stream.completeSent || stream.closeSent) {
        throw new MuxStreamClosedException("stream direction is already ended");
      }
      if (stream.mode() == MuxMode.WS && !stream.headersReceived) {
        throw new IllegalStateException("cannot send WS DATA before HEADERS 101");
      }
      if (stream.queuedDataCost + cost > MuxLimits.INITIAL_STREAM_CREDIT) {
        throw new MuxBackpressureException("per-stream data queue is full");
      }
      if (queuedDataCost + cost > MuxLimits.INITIAL_CONNECTION_CREDIT) {
        throw new MuxBackpressureException("connection data queue is full");
      }
      if (stream.mode() == MuxMode.WS) {
        if (stream.wsOutboundMessageOpen && stream.wsOutboundMessageKind != kind) {
          throw new IllegalArgumentException("WS message fragment changed TEXT/BINARY type");
        }
        if (!stream.wsOutboundMessageOpen) {
          stream.wsOutboundMessageKind = kind;
        }
        stream.wsOutboundMessageOpen = !fin;
      }
      Outbound outbound = Outbound.data(this, stream, kind, fin, payload.clone(), cost, completion);
      stream.outbound.addLast(outbound);
      stream.queuedDataCost += cost;
      queuedDataCost += cost;
      completion.whenComplete(
          (ignored, error) -> {
            if (completion.isCancelled()) {
              cancelQueuedData(outbound);
            }
          });
    }
    pump();
    return completion;
  }

  CompletableFuture<Void> sendHttpEnd(MuxStream stream) {
    Objects.requireNonNull(stream, "stream");
    CompletableFuture<Void> completion = new CompletableFuture<>();
    try {
      synchronized (lock) {
        requireSendableLocked(stream);
        if (stream.mode() != MuxMode.HTTP) {
          throw new IllegalStateException("END requires an HTTP stream");
        }
        if (stream.outboundEnded) {
          throw new MuxStreamClosedException("request END already queued");
        }
        if (stream.completeSent) {
          throw new MuxStreamClosedException("stream already completed");
        }
        stream.outboundEnded = true;
        enqueueManagementLocked(Outbound.end(this, stream, completion));
      }
    } catch (MuxProtocolException error) {
      failConnection(error);
      throw error;
    }
    pump();
    return completion;
  }

  CompletableFuture<Void> sendComplete(MuxStream stream) {
    Objects.requireNonNull(stream, "stream");
    CompletableFuture<Void> completion = new CompletableFuture<>();
    try {
      synchronized (lock) {
        requireSendableLocked(stream);
        if (stream.mode() != MuxMode.HTTP) {
          throw new IllegalStateException("COMPLETE requires an HTTP stream");
        }
        if (!stream.inboundEnded) {
          throw new IllegalStateException("response END has not been received");
        }
        if (stream.unconsumedFrames != 0 || stream.receiveUnacked != 0) {
          throw new IllegalStateException(
              "response data has not been fully consumed and acknowledged");
        }
        if (stream.completeSent) {
          throw new MuxStreamClosedException("COMPLETE already queued");
        }
        stream.completeSent = true;
        enqueueManagementLocked(Outbound.complete(this, stream, completion));
      }
    } catch (MuxProtocolException error) {
      failConnection(error);
      throw error;
    }
    pump();
    return completion;
  }

  CompletableFuture<Void> sendClose(MuxStream stream, MuxClose close) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(close, "close");
    CompletableFuture<Void> completion = new CompletableFuture<>();
    List<Runnable> after = new ArrayList<>();
    try {
      synchronized (lock) {
        requireSendableLocked(stream);
        if (stream.mode() != MuxMode.WS) {
          throw new IllegalStateException("CLOSE requires a WS stream");
        }
        if (!stream.ready || !stream.headersReceived) {
          throw new IllegalStateException("cannot close a WS stream before HEADERS 101");
        }
        if (stream.closeSent) {
          throw new MuxStreamClosedException("CLOSE already queued");
        }
        stream.closeSent = true;
        enqueueManagementLocked(Outbound.close(this, stream, close, completion));
        dropQueuedDataLocked(
            stream, new MuxStreamClosedException("WS close superseded queued DATA"), after);
      }
    } catch (MuxProtocolException error) {
      runAllQuietly(after);
      failConnection(error);
      throw error;
    }
    runAll(after);
    pump();
    return completion;
  }

  CompletableFuture<Void> resetStream(MuxStream stream, MuxReset reset) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(reset, "reset");
    List<Runnable> after = new ArrayList<>();
    CompletableFuture<Void> completion;
    try {
      synchronized (lock) {
        if (stopped) {
          throw closedException("connection is stopped");
        }
        if (!stream.active) {
          throw new MuxStreamClosedException("stream is already closed");
        }
        completion = beginResetLocked(stream, reset, after);
      }
    } catch (MuxProtocolException error) {
      runAllQuietly(after);
      failConnection(error);
      throw error;
    }
    runAll(after);
    pump();
    return completion;
  }

  void consumed(MuxReceivedData data) {
    try {
      synchronized (lock) {
        if (data.isConsumed()) {
          return;
        }
        data.markConsumed();
        if (stopped) {
          return;
        }
        MuxStream stream = data.stream();
        if (!stream.active) {
          // RESET/关闭时已把未归还接收 credit 一次性折算为连接级 WINDOW。
          return;
        }
        stream.pendingWindow += data.creditCost();
        if (stream.unconsumedFrames > 0) {
          stream.unconsumedFrames--;
        }
        if (stream.pendingWindow >= MuxLimits.WINDOW_ACK_THRESHOLD
            || stream.unconsumedFrames == 0) {
          flushWindowLocked(stream);
        }
      }
    } catch (MuxProtocolException error) {
      failConnection(error);
      throw error;
    }
    pump();
  }

  private void handleFrameLocked(MuxFrame frame, List<Runnable> after) {
    switch (frame) {
      case MuxFrame.Open ignored -> throw new MuxProtocolException("only Server may send OPEN");
      case MuxFrame.Ready ready -> handleReadyLocked(ready.streamId(), after);
      case MuxFrame.Headers headers -> handleHeadersLocked(headers, after);
      case MuxFrame.Data data -> handleDataLocked(data, after);
      case MuxFrame.Window window -> handleWindowLocked(window);
      case MuxFrame.End end -> handleEndLocked(end.streamId(), after);
      case MuxFrame.Complete complete -> handleCompleteLocked(complete.streamId());
      case MuxFrame.Reset reset -> handleResetLocked(reset, after);
      case MuxFrame.Close close -> handleCloseLocked(close, after);
    }
  }

  private void handleReadyLocked(long streamId, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(streamId, "READY");
    if (stream == null) {
      return;
    }
    if (stream.ready) {
      throw new MuxProtocolException("duplicate READY for stream " + streamId);
    }
    stream.ready = true;
    after.add(() -> stream.listener.onReady(stream));
  }

  private void handleHeadersLocked(MuxFrame.Headers frame, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(frame.streamId(), "HEADERS");
    if (stream == null) {
      return;
    }
    requireReadyLocked(stream, "HEADERS");
    if (stream.headersReceived) {
      throw new MuxProtocolException("duplicate HEADERS for stream " + frame.streamId());
    }
    if (stream.mode() == MuxMode.WS && frame.headers().status() != 101) {
      throw new MuxProtocolException("WS HEADERS status must be 101");
    }
    stream.headersReceived = true;
    after.add(() -> stream.listener.onHeaders(stream, frame.headers()));
  }

  private void handleDataLocked(MuxFrame.Data frame, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(frame.streamId(), "DATA");
    if (stream == null) {
      receiveLateDataLocked(frame);
      return;
    }
    requireReadyLocked(stream, "DATA");
    if (!stream.headersReceived) {
      throw new MuxProtocolException("DATA before HEADERS");
    }
    if (stream.inboundEnded) {
      throw new MuxProtocolException("DATA after END");
    }
    if (stream.closeReceived) {
      throw new MuxProtocolException("DATA after CLOSE");
    }
    validateDataKindLocked(stream, frame);
    int cost = MuxLimits.dataCost(frame.payload().length);
    if (stream.receiveUnacked + cost > MuxLimits.INITIAL_STREAM_CREDIT) {
      throw new MuxProtocolException("stream receive credit exceeded");
    }
    if (connectionReceiveUnacked + cost > MuxLimits.INITIAL_CONNECTION_CREDIT) {
      throw new MuxProtocolException("connection receive credit exceeded");
    }
    stream.receiveUnacked += cost;
    connectionReceiveUnacked += cost;
    stream.unconsumedFrames++;
    MuxReceivedData data =
        new MuxReceivedData(this, stream, frame.kind(), frame.fin(), frame.payload(), cost);
    after.add(() -> stream.listener.onData(stream, data));
  }

  private void handleWindowLocked(MuxFrame.Window frame) {
    if (frame.streamId() == 0) {
      if (frame.increment() > connectionSendUnacked) {
        throw new MuxProtocolException("connection WINDOW exceeds sent-but-unacknowledged credit");
      }
      connectionSendUnacked -= frame.increment();
      return;
    }
    MuxStream stream = lookupOrClosedLocked(frame.streamId(), "WINDOW");
    if (stream == null) {
      return;
    }
    if (frame.increment() > stream.sendUnacked) {
      throw new MuxProtocolException("stream WINDOW exceeds sent-but-unacknowledged credit");
    }
    stream.sendUnacked -= frame.increment();
  }

  private void handleEndLocked(long streamId, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(streamId, "END");
    if (stream == null) {
      return;
    }
    if (stream.mode() != MuxMode.HTTP) {
      throw new MuxProtocolException("END is only valid for HTTP streams");
    }
    requireReadyLocked(stream, "END");
    if (!stream.headersReceived) {
      throw new MuxProtocolException("END before HEADERS");
    }
    if (stream.inboundEnded) {
      throw new MuxProtocolException("duplicate response END");
    }
    stream.inboundEnded = true;
    after.add(() -> stream.listener.onEnd(stream));
  }

  private void handleCompleteLocked(long streamId) {
    MuxStream stream = lookupOrClosedLocked(streamId, "COMPLETE");
    if (stream == null) {
      return;
    }
    throw new MuxProtocolException("only Server may send COMPLETE");
  }

  private void handleResetLocked(MuxFrame.Reset frame, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(frame.streamId(), "RESET");
    if (stream == null) {
      return;
    }
    terminateStreamLocked(
        stream, new MuxStreamClosedException("stream reset by peer"), true, after, true);
    after.add(() -> stream.listener.onReset(stream, frame.reset()));
  }

  private void handleCloseLocked(MuxFrame.Close frame, List<Runnable> after) {
    MuxStream stream = lookupOrClosedLocked(frame.streamId(), "CLOSE");
    if (stream == null) {
      return;
    }
    if (stream.mode() != MuxMode.WS) {
      throw new MuxProtocolException("CLOSE is only valid for WS streams");
    }
    requireReadyLocked(stream, "CLOSE");
    if (!stream.headersReceived) {
      throw new MuxProtocolException("CLOSE before WS handshake HEADERS");
    }
    if (stream.closeReceived) {
      throw new MuxProtocolException("duplicate CLOSE");
    }
    stream.closeReceived = true;
    if (!stream.closeSent) {
      stream.closeSent = true;
      enqueueManagementLocked(Outbound.close(this, stream, frame.close(), null));
    }
    dropQueuedDataLocked(
        stream, new MuxStreamClosedException("WS close arrived before DATA was sent"), after);
    after.add(() -> stream.listener.onClose(stream, frame.close()));
    if (stream.closeWriteCompleted) {
      terminateStreamLocked(
          stream, new MuxStreamClosedException("WS close completed"), true, after, true);
    }
  }

  private void receiveLateDataLocked(MuxFrame.Data frame) {
    int cost = MuxLimits.dataCost(frame.payload().length);
    if (connectionReceiveUnacked + cost > MuxLimits.INITIAL_CONNECTION_CREDIT) {
      throw new MuxProtocolException("connection receive credit exceeded by late DATA");
    }
    // 迟到的 DATA 只占连接额度；丢弃即消费，立即归还连接级 credit。
    enqueueConnectionWindowLocked(cost);
  }

  private MuxStream lookupOrClosedLocked(long streamId, String frameType) {
    MuxStream stream = streamsById.get(streamId);
    if (stream != null) {
      return stream;
    }
    if (streamId <= maxAllocatedStreamId) {
      return null;
    }
    throw new MuxProtocolException(frameType + " on never allocated stream " + streamId);
  }

  private void validateDataKindLocked(MuxStream stream, MuxFrame.Data frame) {
    if (stream.mode() == MuxMode.HTTP) {
      if (frame.kind() != MuxDataKind.HTTP) {
        throw new MuxProtocolException("HTTP stream received WS DATA");
      }
      return;
    }
    if (frame.kind() == MuxDataKind.HTTP) {
      throw new MuxProtocolException("WS stream received HTTP DATA");
    }
    if (stream.wsMessageOpen) {
      if (stream.wsMessageKind != frame.kind()) {
        throw new MuxProtocolException("WS message fragment changed TEXT/BINARY type");
      }
    } else {
      stream.wsMessageOpen = true;
      stream.wsMessageKind = frame.kind();
    }
    if (frame.fin()) {
      stream.wsMessageOpen = false;
    }
  }

  private void requireReadyLocked(MuxStream stream, String frameType) {
    if (!stream.ready) {
      throw new MuxProtocolException(frameType + " before READY");
    }
  }

  private void requireSendableLocked(MuxStream stream) {
    if (stopped) {
      throw closedException("connection is stopped");
    }
    if (!stream.active) {
      throw new MuxStreamClosedException("stream is already closed");
    }
  }

  private CompletableFuture<Void> beginResetLocked(
      MuxStream stream, MuxReset reset, List<Runnable> after) {
    stream.resetSent = true;
    cancelCloseTimerLocked(stream);
    Throwable closedCause = new MuxStreamClosedException("stream reset locally");
    terminateStreamLocked(stream, closedCause, false, after, true);
    if (stream.streamId == 0) {
      after.add(
          () ->
              stream.opened.completeExceptionally(
                  new MuxStreamClosedException("stream closed before OPEN was written")));
      after.add(() -> stream.closed.complete(null));
      liveStreams.remove(stream);
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> completion = new CompletableFuture<>();
    enqueueManagementLocked(Outbound.reset(this, stream, reset, completion));
    return completion;
  }

  private void cancelQueuedData(Outbound outbound) {
    boolean removed;
    synchronized (lock) {
      removed = outbound.stream.outbound.remove(outbound);
      if (removed) {
        outbound.stream.queuedDataCost -= outbound.dataCost;
        queuedDataCost -= outbound.dataCost;
      }
    }
    if (removed) {
      pump();
    }
  }

  private void terminateStreamLocked(
      MuxStream stream,
      Throwable cause,
      boolean completeClosed,
      List<Runnable> after,
      boolean dropInbound) {
    if (stream.active) {
      stream.active = false;
      streams.remove(stream);
      if (stream.streamId != 0) {
        streamsById.remove(stream.streamId);
      }
      if (roundRobin >= streams.size()) {
        roundRobin = 0;
      }
    }
    cancelCloseTimerLocked(stream);
    clearStreamOutboundLocked(stream, cause, after);
    if (dropInbound) {
      dropInboundLocked(stream);
    }
    if (completeClosed) {
      liveStreams.remove(stream);
      if (!stream.closed.isDone()) {
        after.add(() -> stream.closed.complete(null));
      }
    }
  }

  private void clearStreamOutboundLocked(MuxStream stream, Throwable cause, List<Runnable> after) {
    for (Outbound outbound : stream.outbound) {
      if (outbound.kind == OutboundKind.DATA) {
        stream.queuedDataCost -= outbound.dataCost;
        queuedDataCost -= outbound.dataCost;
      } else if (outbound.ordered && management.remove(outbound)) {
        managementFrames--;
        managementBytes -= outbound.wireSize;
      }
      if (outbound.completion != null && !outbound.completion.isDone()) {
        after.add(() -> outbound.completion.completeExceptionally(cause));
      }
    }
    stream.outbound.clear();
    Iterator<Outbound> iterator = management.iterator();
    while (iterator.hasNext()) {
      Outbound outbound = iterator.next();
      if (outbound.kind == OutboundKind.WINDOW && outbound.stream == stream) {
        iterator.remove();
        managementFrames--;
        managementBytes -= outbound.wireSize;
      }
    }
  }

  private void dropQueuedDataLocked(MuxStream stream, Throwable cause, List<Runnable> after) {
    Iterator<Outbound> iterator = stream.outbound.iterator();
    while (iterator.hasNext()) {
      Outbound outbound = iterator.next();
      if (outbound.kind != OutboundKind.DATA) {
        continue;
      }
      iterator.remove();
      stream.queuedDataCost -= outbound.dataCost;
      queuedDataCost -= outbound.dataCost;
      if (outbound.completion != null && !outbound.completion.isDone()) {
        after.add(() -> outbound.completion.completeExceptionally(cause));
      }
    }
  }

  private void dropInboundLocked(MuxStream stream) {
    long credit = stream.receiveUnacked;
    stream.receiveUnacked = 0;
    stream.pendingWindow = 0;
    stream.unconsumedFrames = 0;
    if (credit == 0) {
      return;
    }
    connectionReceiveUnacked -= credit;
    enqueueConnectionWindowLocked(credit);
  }

  private void flushWindowLocked(MuxStream stream) {
    long amount = stream.pendingWindow;
    if (amount == 0) {
      return;
    }
    stream.pendingWindow = 0;
    if (stream.receiveUnacked < amount || connectionReceiveUnacked < amount) {
      throw new MuxProtocolException("internal receive credit underflow");
    }
    stream.receiveUnacked -= amount;
    connectionReceiveUnacked -= amount;
    enqueueManagementLocked(Outbound.window(this, stream, stream.streamId, amount));
    enqueueConnectionWindowLocked(amount);
  }

  private void enqueueConnectionWindowLocked(long amount) {
    if (amount <= 0) {
      return;
    }
    enqueueManagementLocked(Outbound.window(this, null, 0, amount));
  }

  private void enqueueManagementLocked(Outbound outbound) {
    if (managementFrames + 1 > MuxLimits.MAX_MANAGEMENT_FRAMES
        || managementBytes + outbound.wireSize > MuxLimits.MAX_MANAGEMENT_BYTES) {
      throw new MuxProtocolException("management frame queue exhausted");
    }
    managementFrames++;
    managementBytes += outbound.wireSize;
    management.addLast(outbound);
    if (outbound.ordered) {
      outbound.stream.outbound.addLast(outbound);
    }
  }

  private void scheduleCloseTimeoutLocked(MuxStream stream) {
    if (stream.closeTimer != null && !stream.closeTimer.isDone()) {
      return;
    }
    stream.closeTimer =
        timer.schedule(
            () -> onCloseTimeout(stream),
            Math.max(1L, wsCloseTimeout.toMillis()),
            TimeUnit.MILLISECONDS);
  }

  private void cancelCloseTimerLocked(MuxStream stream) {
    if (stream.closeTimer != null) {
      stream.closeTimer.cancel(false);
      stream.closeTimer = null;
    }
  }

  private void onCloseTimeout(MuxStream stream) {
    List<Runnable> after = new ArrayList<>();
    try {
      synchronized (lock) {
        if (stopped || !stream.active || stream.closeReceived || !stream.closeSent) {
          return;
        }
        beginResetLocked(
            stream, new MuxReset("WS_CLOSE_TIMEOUT", "close handshake timed out"), after);
      }
    } catch (MuxProtocolException error) {
      runAllQuietly(after);
      failConnection(error);
      return;
    }
    runAll(after);
    pump();
  }

  private void pump() {
    synchronized (pumpGate) {
      if (pumping) {
        pumpRequested = true;
        return;
      }
      pumping = true;
      try {
        do {
          pumpRequested = false;
          while (true) {
            Selected selected = null;
            RuntimeException selectionError = null;
            synchronized (lock) {
              if (stopped || writing) {
                break;
              }
              try {
                selected = selectNextLocked();
              } catch (RuntimeException error) {
                selectionError = error;
              }
              if (selected != null) {
                writing = true;
                inFlight = selected.outbound;
              }
            }
            if (selectionError != null) {
              failConnection(selectionError);
              break;
            }
            if (selected == null) {
              break;
            }
            Selected current = selected;
            CompletionStage<Void> stage;
            try {
              stage = transport.write(current.wire);
              if (stage == null) {
                throw new IllegalStateException("transport returned a null write completion");
              }
            } catch (RuntimeException error) {
              failConnection(error);
              break;
            }
            stage.whenComplete((ignored, error) -> onWriteCompleted(current.outbound, error));
            synchronized (lock) {
              if (writing) {
                break;
              }
            }
          }
        } while (pumpRequested && !stopped);
      } finally {
        pumping = false;
      }
    }
  }

  private Selected selectNextLocked() {
    Iterator<Outbound> iterator = management.iterator();
    while (iterator.hasNext()) {
      Outbound outbound = iterator.next();
      boolean streamHead =
          outbound.ordered
              && outbound.stream != null
              && outbound.stream.outbound.peekFirst() == outbound;
      boolean eligible =
          outbound.kind == OutboundKind.WINDOW
              || (streamHead && (outbound.kind != OutboundKind.END || outbound.stream.ready));
      if (!eligible) {
        continue;
      }
      iterator.remove();
      managementFrames--;
      managementBytes -= outbound.wireSize;
      if (outbound.ordered) {
        outbound.stream.outbound.removeFirst();
      }
      if (outbound.kind == OutboundKind.OPEN) {
        if (nextStreamId > MuxFrame.MAX_STREAM_ID) {
          throw new MuxProtocolException("stream id space exhausted");
        }
        outbound.stream.streamId = nextStreamId++;
        maxAllocatedStreamId = outbound.stream.streamId;
        streamsById.put(outbound.stream.streamId, outbound.stream);
      }
      return new Selected(outbound, encodeOutboundLocked(outbound));
    }
    if (streams.isEmpty()) {
      return null;
    }
    int size = streams.size();
    for (int offset = 0; offset < size; offset++) {
      int index = (roundRobin + offset) % size;
      MuxStream stream = streams.get(index);
      if (!stream.ready || stream.streamId == 0) {
        continue;
      }
      Outbound head = stream.outbound.peekFirst();
      if (head == null || head.kind != OutboundKind.DATA) {
        continue;
      }
      if (stream.sendUnacked + head.dataCost > MuxLimits.INITIAL_STREAM_CREDIT) {
        continue;
      }
      if (connectionSendUnacked + head.dataCost > MuxLimits.INITIAL_CONNECTION_CREDIT) {
        continue;
      }
      stream.outbound.removeFirst();
      stream.queuedDataCost -= head.dataCost;
      queuedDataCost -= head.dataCost;
      stream.sendUnacked += head.dataCost;
      connectionSendUnacked += head.dataCost;
      roundRobin = (index + 1) % size;
      return new Selected(head, encodeOutboundLocked(head));
    }
    return null;
  }

  private byte[] encodeOutboundLocked(Outbound outbound) {
    MuxFrame frame =
        switch (outbound.kind) {
          case OPEN -> new MuxFrame.Open(outbound.stream.streamId, outbound.open);
          case DATA -> new MuxFrame.Data(
              outbound.stream.streamId, outbound.dataKind, outbound.fin, outbound.payload);
          case END -> new MuxFrame.End(outbound.stream.streamId);
          case COMPLETE -> new MuxFrame.Complete(outbound.stream.streamId);
          case CLOSE -> new MuxFrame.Close(outbound.stream.streamId, outbound.close);
          case RESET -> new MuxFrame.Reset(outbound.stream.streamId, outbound.reset);
          case WINDOW -> new MuxFrame.Window(outbound.windowStreamId, outbound.windowIncrement);
        };
    return codec.encode(frame);
  }

  private void onWriteCompleted(Outbound outbound, Throwable error) {
    List<Runnable> after = new ArrayList<>();
    Throwable failureToApply = error;
    synchronized (lock) {
      if (stopped) {
        return;
      }
      if (error == null) {
        writing = false;
        if (inFlight == outbound) {
          inFlight = null;
        }
        if (outbound.completion != null && !outbound.completion.isDone()) {
          after.add(() -> outbound.completion.complete(null));
        }
        try {
          switch (outbound.kind) {
            case OPEN -> {
              if (!outbound.stream.opened.isDone()) {
                after.add(() -> outbound.stream.opened.complete(null));
              }
            }
            case CLOSE -> {
              outbound.stream.closeWriteCompleted = true;
              if (outbound.stream.closeReceived) {
                terminateStreamLocked(
                    outbound.stream,
                    new MuxStreamClosedException("WS close completed"),
                    true,
                    after,
                    true);
              } else if (outbound.stream.active) {
                scheduleCloseTimeoutLocked(outbound.stream);
              }
            }
            case RESET -> {
              liveStreams.remove(outbound.stream);
              if (!outbound.stream.closed.isDone()) {
                after.add(() -> outbound.stream.closed.complete(null));
              }
            }
            case COMPLETE -> {
              terminateStreamLocked(
                  outbound.stream,
                  new MuxStreamClosedException("HTTP exchange completed"),
                  true,
                  after,
                  true);
            }
            case DATA, END, WINDOW -> {}
          }
        } catch (MuxProtocolException protocolError) {
          failureToApply = protocolError;
        }
      }
    }
    if (failureToApply != null) {
      failConnection(failureToApply);
      return;
    }
    runAll(after);
    pump();
  }

  private void failConnection(Throwable cause) {
    terminate(cause, true);
  }

  private void terminate(Throwable cause, boolean notifyListener) {
    List<Runnable> after = new ArrayList<>();
    synchronized (lock) {
      if (stopped) {
        return;
      }
      stopped = true;
      failure = cause;
      writing = false;
      if (inFlight != null) {
        Outbound inFlightOutbound = inFlight;
        inFlight = null;
        if (inFlightOutbound.completion != null && !inFlightOutbound.completion.isDone()) {
          after.add(() -> inFlightOutbound.completion.completeExceptionally(cause));
        }
      }
      for (MuxStream stream : new ArrayList<>(liveStreams)) {
        stream.active = false;
        cancelCloseTimerLocked(stream);
        clearStreamOutboundLocked(stream, cause, after);
        stream.receiveUnacked = 0;
        stream.pendingWindow = 0;
        stream.unconsumedFrames = 0;
        if (!stream.opened.isDone()) {
          after.add(() -> stream.opened.completeExceptionally(cause));
        }
        if (!stream.closed.isDone()) {
          after.add(() -> stream.closed.completeExceptionally(cause));
        }
      }
      for (Outbound outbound : management) {
        if (outbound.completion != null && !outbound.completion.isDone()) {
          after.add(() -> outbound.completion.completeExceptionally(cause));
        }
      }
      management.clear();
      managementFrames = 0;
      managementBytes = 0;
      streams.clear();
      streamsById.clear();
      liveStreams.clear();
      queuedDataCost = 0;
      connectionSendUnacked = 0;
      connectionReceiveUnacked = 0;
    }
    runAll(after);
    try {
      transport.close();
    } catch (RuntimeException ignored) {
      // 关闭底层资源失败不改变已经确定的 fail-stop 结果。
    }
    if (notifyListener) {
      try {
        listener.onFailure(this, cause);
      } catch (RuntimeException ignored) {
        // 失败通知不能掩盖原始失败原因。
      }
    }
  }

  private IllegalStateException closedException(String message) {
    return failure instanceof MuxProtocolException protocol
        ? new MuxConnectionClosedException(message + ": " + protocol.getMessage())
        : new MuxConnectionClosedException(message);
  }

  private static void runAllQuietly(List<Runnable> actions) {
    for (Runnable action : actions) {
      try {
        action.run();
      } catch (RuntimeException ignored) {
        // 失败收敛时优先完成全部等待者，不能让单个回调掩盖原始错误。
      }
    }
  }

  private static void runAll(List<Runnable> actions) {
    for (Runnable action : actions) {
      action.run();
    }
  }

  long connectionSendUnacked() {
    synchronized (lock) {
      return connectionSendUnacked;
    }
  }

  long connectionReceiveUnacked() {
    synchronized (lock) {
      return connectionReceiveUnacked;
    }
  }

  int managementFrames() {
    synchronized (lock) {
      return managementFrames;
    }
  }

  long maxAllocatedStreamId() {
    synchronized (lock) {
      return maxAllocatedStreamId;
    }
  }

  private enum OutboundKind {
    OPEN,
    DATA,
    END,
    COMPLETE,
    CLOSE,
    RESET,
    WINDOW
  }

  static final class Outbound {

    final OutboundKind kind;
    final MuxStream stream;
    final MuxOpen open;
    final MuxDataKind dataKind;
    final boolean fin;
    final byte[] payload;
    final MuxClose close;
    final MuxReset reset;
    final long windowStreamId;
    final long windowIncrement;
    final int dataCost;
    final int wireSize;
    final boolean ordered;
    final CompletableFuture<Void> completion;

    private Outbound(
        OutboundKind kind,
        MuxStream stream,
        MuxOpen open,
        MuxDataKind dataKind,
        boolean fin,
        byte[] payload,
        MuxClose close,
        MuxReset reset,
        long windowStreamId,
        long windowIncrement,
        int dataCost,
        int wireSize,
        boolean ordered,
        CompletableFuture<Void> completion) {
      this.kind = kind;
      this.stream = stream;
      this.open = open;
      this.dataKind = dataKind;
      this.fin = fin;
      this.payload = payload;
      this.close = close;
      this.reset = reset;
      this.windowStreamId = windowStreamId;
      this.windowIncrement = windowIncrement;
      this.dataCost = dataCost;
      this.wireSize = wireSize;
      this.ordered = ordered;
      this.completion = completion;
    }

    static Outbound open(MuxConnection connection, MuxStream stream, MuxOpen request) {
      int wireSize =
          MuxLimits.HEADER_BYTES + connection.codec.encode(new MuxFrame.Open(1, request)).length;
      return new Outbound(
          OutboundKind.OPEN,
          stream,
          request,
          null,
          false,
          null,
          null,
          null,
          0,
          0,
          0,
          wireSize,
          true,
          new CompletableFuture<>());
    }

    static Outbound data(
        MuxConnection connection,
        MuxStream stream,
        MuxDataKind kind,
        boolean fin,
        byte[] payload,
        int dataCost,
        CompletableFuture<Void> completion) {
      return new Outbound(
          OutboundKind.DATA,
          stream,
          null,
          kind,
          fin,
          payload,
          null,
          null,
          0,
          0,
          dataCost,
          MuxLimits.HEADER_BYTES + payload.length,
          false,
          completion);
    }

    static Outbound end(
        MuxConnection connection, MuxStream stream, CompletableFuture<Void> completion) {
      return control(OutboundKind.END, stream, null, null, completion);
    }

    static Outbound complete(
        MuxConnection connection, MuxStream stream, CompletableFuture<Void> completion) {
      return control(OutboundKind.COMPLETE, stream, null, null, completion);
    }

    static Outbound close(
        MuxConnection connection,
        MuxStream stream,
        MuxClose close,
        CompletableFuture<Void> completion) {
      int wireSize =
          MuxLimits.HEADER_BYTES + connection.codec.encode(new MuxFrame.Close(1, close)).length;
      return new Outbound(
          OutboundKind.CLOSE,
          stream,
          null,
          null,
          false,
          null,
          close,
          null,
          0,
          0,
          0,
          wireSize,
          true,
          completion);
    }

    static Outbound reset(
        MuxConnection connection,
        MuxStream stream,
        MuxReset reset,
        CompletableFuture<Void> completion) {
      int wireSize =
          MuxLimits.HEADER_BYTES + connection.codec.encode(new MuxFrame.Reset(1, reset)).length;
      return new Outbound(
          OutboundKind.RESET,
          stream,
          null,
          null,
          false,
          null,
          null,
          reset,
          0,
          0,
          0,
          wireSize,
          true,
          completion);
    }

    static Outbound window(
        MuxConnection connection, MuxStream stream, long streamId, long increment) {
      int wireSize = MuxLimits.HEADER_BYTES + 4;
      return new Outbound(
          OutboundKind.WINDOW,
          stream,
          null,
          null,
          false,
          null,
          null,
          null,
          streamId,
          increment,
          0,
          wireSize,
          false,
          null);
    }

    private static Outbound control(
        OutboundKind kind,
        MuxStream stream,
        MuxReset reset,
        MuxClose close,
        CompletableFuture<Void> completion) {
      return new Outbound(
          kind,
          stream,
          null,
          null,
          false,
          null,
          close,
          reset,
          0,
          0,
          0,
          MuxLimits.HEADER_BYTES,
          true,
          completion);
    }
  }

  private record Selected(Outbound outbound, byte[] wire) {}
}
