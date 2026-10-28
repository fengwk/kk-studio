package fun.fengwk.kkstudio.ide.mux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 意图：用可控物理写 fake 证明真实 mux 行为 —— 严格连续 streamId、单写入者与公平 round-robin、END 不越过同流 DATA、两级 credit、 下游消费后才
 * ACK、RESET/关闭回收连接额度、HTTP half-close/COMPLETE、WS 分段/CLOSE、首写失败 fail-stop 与 RESET 竞态。
 */
class MuxConnectionTest {

  private static final MuxWireCodec CODEC = new MuxWireCodec();
  private static final UUID VIEW_ID = UUID.fromString("2a4e6b6e-8f1c-4d8a-9c2d-5b7e1f3a9c01");

  @Test
  void allocatesConsecutiveStreamIdsOnlyWhenOpenWriteStarts() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream first = connection.open(httpOpen(), new TestListener());
    transport.awaitWrites(1);
    assertEquals(1L, first.streamId().getAsLong());
    assertFalse(first.opened().toCompletableFuture().isDone());

    MuxStream second = connection.open(httpOpen(), new TestListener());
    assertTrue(second.streamId().isEmpty());
    assertFalse(second.opened().toCompletableFuture().isDone());

    transport.completeNext();
    transport.awaitWrites(2);
    assertEquals(2L, second.streamId().getAsLong());
    assertTrue(first.opened().toCompletableFuture().isDone());
    assertFalse(second.opened().toCompletableFuture().isDone());
    transport.completeNext();
    assertTrue(second.opened().toCompletableFuture().isDone());
  }

  @Test
  void resetBeforeOpenWriteDoesNotConsumeStreamIdOrEmitReset() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream first = connection.open(httpOpen(), new TestListener());
    MuxStream abandoned = connection.open(httpOpen(), new TestListener());
    abandoned.sendHttpData(new byte[10]);

    CompletableFuture<Void> reset = abandoned.reset("CANCELLED", "not started");
    assertTrue(reset.isDone());
    assertTrue(abandoned.closed().toCompletableFuture().isDone());
    assertTrue(abandoned.opened().toCompletableFuture().isCompletedExceptionally());
    assertTrue(abandoned.streamId().isEmpty());
    assertEquals(1, transport.writeCount());

    transport.completeNext();
    MuxStream next = connection.open(httpOpen(), new TestListener());
    transport.awaitWrites(2);
    assertEquals(2L, next.streamId().getAsLong());
    transport.completeNext();
    assertEquals(2L, connection.maxAllocatedStreamId());
  }

  @Test
  void roundRobinIsFairAndEndNeverOvertakesItsOwnData() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream a = connection.open(httpOpen(), new TestListener());
    MuxStream b = connection.open(httpOpen(), new TestListener());
    transport.completeNext();
    transport.completeNext();
    ready(connection, a);
    ready(connection, b);

    CompletableFuture<Void> a1 = a.sendHttpData(new byte[] {1});
    CompletableFuture<Void> a2 = a.sendHttpData(new byte[] {2});
    CompletableFuture<Void> aEnd = a.sendEnd();
    CompletableFuture<Void> b1 = b.sendHttpData(new byte[] {1});
    CompletableFuture<Void> b2 = b.sendHttpData(new byte[] {2});
    assertFalse(aEnd.isDone());
    assertEquals(1, transport.pendingCount());

    while (transport.pendingCount() > 0) {
      transport.completeNext();
    }
    assertTrue(a1.isDone());
    assertTrue(a2.isDone());
    assertTrue(aEnd.isDone());
    assertTrue(b1.isDone());
    assertTrue(b2.isDone());
    assertEquals(1, transport.maxPendingCount());
    assertThrows(MuxStreamClosedException.class, () -> a.sendHttpData(new byte[0]));

    List<MuxFrame> frames = frames(transport);
    List<MuxFrame> streamFrames = frames.subList(2, frames.size());
    assertEquals(MuxFrameType.DATA, streamFrames.get(0).type());
    assertEquals(a.streamId().getAsLong(), streamFrames.get(0).streamId());
    assertEquals(MuxFrameType.DATA, streamFrames.get(1).type());
    assertEquals(b.streamId().getAsLong(), streamFrames.get(1).streamId());
    assertEquals(MuxFrameType.DATA, streamFrames.get(2).type());
    assertEquals(a.streamId().getAsLong(), streamFrames.get(2).streamId());
    assertInstanceOf(MuxFrame.End.class, streamFrames.get(3));
    assertEquals(a.streamId().getAsLong(), streamFrames.get(3).streamId());
    assertEquals(MuxFrameType.DATA, streamFrames.get(4).type());
    assertEquals(b.streamId().getAsLong(), streamFrames.get(4).streamId());
  }

  @Test
  void perStreamCreditBlocksThenWindowReleasesExactlyOneFrame() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    MuxStream stream = openReadyHttp(connection, new TestListener());
    for (int i = 0; i < 4; i++) {
      stream.sendHttpData(new byte[65536]);
    }
    assertEquals(4, dataFrameCount(transport));
    CompletableFuture<Void> fifth = stream.sendHttpData(new byte[65536]);
    assertFalse(fifth.isDone());
    assertEquals(4, dataFrameCount(transport));
    assertEquals(4L * 65536, connection.connectionSendUnacked());

    connection.receive(CODEC.encode(new MuxFrame.Window(stream.streamId().getAsLong(), 65536)));
    assertTrue(fifth.isDone());
    assertEquals(5, dataFrameCount(transport));
    assertEquals(5L * 65536, connection.connectionSendUnacked());

    assertThrows(
        MuxProtocolException.class,
        () ->
            connection.receive(
                CODEC.encode(new MuxFrame.Window(stream.streamId().getAsLong(), 4L * 65536 + 1))));
  }

  @Test
  void aggregateSendCreditBoundsAllStreams() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    List<MuxStream> streams = new ArrayList<>();
    for (int i = 0; i < 17; i++) {
      streams.add(openReadyHttp(connection, new TestListener()));
    }
    for (int i = 0; i < 16; i++) {
      for (int frame = 0; frame < 4; frame++) {
        streams.get(i).sendHttpData(new byte[65536]);
      }
    }
    assertEquals(4L * 1024 * 1024, connection.connectionSendUnacked());
    assertEquals(64, dataFrameCount(transport));
    CompletableFuture<Void> blocked = streams.get(16).sendHttpData(new byte[65536]);
    assertFalse(blocked.isDone());
    assertEquals(64, dataFrameCount(transport));

    connection.receive(CODEC.encode(new MuxFrame.Window(0, 65536)));
    assertTrue(blocked.isDone());
    assertEquals(65, dataFrameCount(transport));
    assertEquals(4L * 1024 * 1024, connection.connectionSendUnacked());
  }

  @Test
  void inboundCreditIsReturnedOnlyAfterDownstreamConsumption() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    byte[] first = new byte[40 * 1024];
    byte[] second = new byte[40 * 1024];
    receiveData(connection, stream, MuxDataKind.HTTP, false, first);
    receiveData(connection, stream, MuxDataKind.HTTP, false, second);
    assertEquals(2, listener.data.size());
    assertEquals(0, windowFrameCount(transport));
    assertEquals(80L * 1024, connection.connectionReceiveUnacked());

    listener.data.get(0).consumed();
    assertEquals(0, windowFrameCount(transport));
    listener.data.get(0).consumed();
    assertEquals(0, windowFrameCount(transport));
    listener.data.get(1).consumed();
    assertEquals(2, windowFrameCount(transport));
    assertEquals(0, connection.connectionReceiveUnacked());

    List<MuxFrame.Window> windows = windows(transport);
    assertEquals(stream.streamId().getAsLong(), windows.get(0).streamId());
    assertEquals(80L * 1024, windows.get(0).increment());
    assertEquals(0, windows.get(1).streamId());
    assertEquals(80L * 1024, windows.get(1).increment());
    listener.data.get(1).consumed();
    assertEquals(2, windowFrameCount(transport));
  }

  @Test
  void perStreamReceiveCreditRejectsOverrun() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    for (int i = 0; i < 4; i++) {
      receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[65536]);
    }
    assertEquals(4L * 65536, connection.connectionReceiveUnacked());
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1]));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
    assertEquals(4, listener.data.size());
  }

  @Test
  void aggregateReceiveCreditRejectsOverrun() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    List<MuxStream> streams = new ArrayList<>();
    for (int i = 0; i < 17; i++) {
      MuxStream stream = openReadyHttp(connection, new TestListener());
      receiveHeaders(connection, stream, 200);
      streams.add(stream);
    }
    for (int i = 0; i < 16; i++) {
      for (int frame = 0; frame < 4; frame++) {
        receiveData(connection, streams.get(i), MuxDataKind.HTTP, false, new byte[65536]);
      }
    }
    assertEquals(4L * 1024 * 1024, connection.connectionReceiveUnacked());
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, streams.get(16), MuxDataKind.HTTP, false, new byte[1]));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
  }

  @Test
  void completeRequiresConsumedResponseAndYieldsCompleteAfterEnd() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    assertThrows(IllegalStateException.class, stream::complete);

    receiveData(
        connection, stream, MuxDataKind.HTTP, false, "body".getBytes(StandardCharsets.UTF_8));
    receiveEnd(connection, stream);
    assertThrows(IllegalStateException.class, stream::complete);

    listener.data.get(0).consumed();
    assertThrows(IllegalStateException.class, stream::complete);
    CompletableFuture<Void> requestEnd = stream.sendEnd();
    assertTrue(requestEnd.isDone());
    CompletableFuture<Void> complete = stream.complete();
    assertTrue(complete.isDone());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertEquals(0, connection.activeStreamCount());
    assertEquals(MuxMode.HTTP, stream.mode());
    assertEquals(VIEW_ID, stream.openRequest().viewId());
    assertFalse(stream.isOpen());
    assertTrue(stream.isReady());
    assertThrows(MuxStreamClosedException.class, () -> stream.sendHttpData(new byte[0]));

    List<MuxFrameType> types = frames(transport).stream().map(MuxFrame::type).toList();
    int endIndex = types.indexOf(MuxFrameType.END);
    int completeIndex = types.indexOf(MuxFrameType.COMPLETE);
    assertTrue(endIndex >= 0);
    assertTrue(completeIndex > endIndex);
  }

  @Test
  void remoteResetKeepsUnconsumedBufferChargedAndLateDataWithoutTombstone() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    long streamId = stream.streamId().getAsLong();
    receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1000]);
    assertEquals(0, windowFrameCount(transport));

    connection.receive(
        CODEC.encode(new MuxFrame.Reset(streamId, new MuxReset("UPSTREAM_FAILED", "failed"))));
    assertEquals(1, listener.resets.size());
    assertEquals(0, connection.activeStreamCount());
    assertEquals(1000L, connection.connectionReceiveUnacked());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertEquals(0, windowFrameCount(transport));

    MuxReceivedData received = listener.data.get(0);
    received.consumed();
    assertEquals(1, windowFrameCount(transport));
    assertEquals(1000L, windows(transport).get(0).increment());
    assertEquals(0, connection.connectionReceiveUnacked());
    listener.data.get(0).consumed();
    assertEquals(1, windowFrameCount(transport));

    receiveData(connection, streamId, MuxDataKind.HTTP, false, new byte[700]);
    assertEquals(1, listener.data.size());
    List<MuxFrame.Window> afterLate = windows(transport);
    assertEquals(2, afterLate.size());
    assertEquals(700L, afterLate.get(afterLate.size() - 1).increment());
    assertEquals(0, connection.connectionReceiveUnacked());
    connection.receive(CODEC.encode(new MuxFrame.Window(streamId, 123456)));
    connection.receive(CODEC.encode(new MuxFrame.Complete(streamId)));
    assertTrue(connection.isOpen());

    MuxStream next = connection.open(httpOpen(), new TestListener());
    assertEquals(2L, next.streamId().getAsLong());
    assertEquals(2L, connection.maxAllocatedStreamId());
  }

  @Test
  void remoteResetReturnsOnlyConsumedCreditAndKeepsDeliveredBuffersCharged() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    long streamId = stream.streamId().getAsLong();
    receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1000]);
    receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1000]);

    // 第一帧已消费但未达到 flush 阈值；RESET 只能收回这部分 pendingWindow。
    listener.data.get(0).consumed();
    assertEquals(2000L, connection.connectionReceiveUnacked());
    assertEquals(0, windowFrameCount(transport));

    connection.receive(
        CODEC.encode(new MuxFrame.Reset(streamId, new MuxReset("UPSTREAM_FAILED", "failed"))));
    assertEquals(1, windowFrameCount(transport));
    assertEquals(1000L, windows(transport).get(0).increment());
    assertEquals(1000L, connection.connectionReceiveUnacked());

    // 已投递但未消费的片段保持占用，消费后只归还连接级额度。
    listener.data.get(1).consumed();
    assertEquals(2, windowFrameCount(transport));
    assertEquals(1000L, windows(transport).get(1).increment());
    assertEquals(0, connection.connectionReceiveUnacked());
  }

  @Test
  void resetPendingDeliveredBuffersRemainChargedAcrossOpenReset() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    TestListener listener = new TestListener();
    long perStream = 4L * 65536;

    for (int round = 0; round < 16; round++) {
      MuxStream stream = openReadyHttp(connection, listener);
      receiveHeaders(connection, stream, 200);
      for (int frame = 0; frame < 4; frame++) {
        receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[65536]);
      }
      stream.reset("CANCELLED", "adapter still holds delivered buffers");
      assertEquals((round + 1) * perStream, connection.connectionReceiveUnacked());
    }
    assertEquals(4L * 1024 * 1024, connection.connectionReceiveUnacked());
    assertEquals(0, connection.activeStreamCount());

    MuxStream overflow = openReadyHttp(connection, listener);
    receiveHeaders(connection, overflow, 200);
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, overflow, MuxDataKind.HTTP, false, new byte[1]));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
  }

  @Test
  void lateDataChargesConnectionCreditUntilWindowWriteCompletes() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream stream = connection.open(httpOpen(), new TestListener());
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 200);
    long streamId = stream.streamId().getAsLong();

    CompletableFuture<Void> reset = stream.reset("CANCELLED", "writer is blocked");
    assertFalse(reset.isDone());
    receiveData(connection, streamId, MuxDataKind.HTTP, false, new byte[1]);
    assertEquals(512L, connection.connectionReceiveUnacked());
    assertEquals(0, windowFrameCount(transport));

    transport.completeNext();
    assertTrue(reset.isDone());
    assertEquals(512L, connection.connectionReceiveUnacked());
    assertEquals(1, windowFrameCount(transport));

    transport.completeNext();
    assertEquals(0, connection.connectionReceiveUnacked());
    assertEquals(512L, windows(transport).get(0).increment());
  }

  @Test
  void lateDataRespectsAggregateCreditWhileWriterIsBlocked() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream stream = connection.open(httpOpen(), new TestListener());
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 200);
    long streamId = stream.streamId().getAsLong();
    stream.reset("CANCELLED", "writer is blocked");

    for (int i = 0; i < 64; i++) {
      receiveData(connection, streamId, MuxDataKind.HTTP, false, new byte[65536]);
    }
    assertEquals(4L * 1024 * 1024, connection.connectionReceiveUnacked());
    assertEquals(0, windowFrameCount(transport));
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, streamId, MuxDataKind.HTTP, false, new byte[1]));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
  }

  @Test
  void localResetReleasesInboundCreditAndCancelsQueuedData() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyHttp(connection, listener);
    receiveHeaders(connection, stream, 200);
    receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[900]);
    assertEquals(900L, connection.connectionReceiveUnacked());

    stream.sendHttpData(new byte[1000]);
    CompletableFuture<Void> cancelled = stream.sendHttpData(new byte[1000]);
    CompletableFuture<Void> reset = stream.reset("CANCELLED", "client stopped");
    assertTrue(cancelled.isCompletedExceptionally());
    assertFalse(reset.isDone());
    assertEquals(0, connection.activeStreamCount());
    assertEquals(900L, connection.connectionReceiveUnacked());

    listener.data.get(0).consumed();
    assertEquals(900L, connection.connectionReceiveUnacked());

    while (transport.pendingCount() > 0) {
      transport.completeNext();
    }
    assertTrue(reset.isDone());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertTrue(connection.isOpen());
    assertEquals(0, connection.connectionReceiveUnacked());
    assertEquals(1, resetFrames(transport).size());
  }

  @Test
  void resetRacingWithInFlightWriteKeepsWireOrderAndClosesOnce() throws Exception {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream stream = connection.open(httpOpen(), new TestListener());
    transport.completeNext();
    ready(connection, stream);
    CompletableFuture<Void> first = stream.sendHttpData(new byte[] {1});
    CompletableFuture<Void> queued = stream.sendHttpData(new byte[] {2});
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<CompletableFuture<Void>> resetFuture = new AtomicReference<>();
    try {
      Future<?> writer =
          executor.submit(
              () -> {
                await(start);
                transport.completeNext();
                return null;
              });
      Future<?> resetter =
          executor.submit(
              () -> {
                await(start);
                resetFuture.set(stream.reset("CANCELLED", "race"));
                return null;
              });
      start.countDown();
      writer.get(3, TimeUnit.SECONDS);
      resetter.get(3, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    while (transport.pendingCount() > 0) {
      transport.completeNext();
    }
    assertTrue(resetFuture.get().isDone());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertTrue(first.isDone());
    assertTrue(queued.isDone());
    List<MuxFrame> frames = frames(transport);
    assertEquals(1, frames.stream().filter(MuxFrame.Reset.class::isInstance).count());
    assertTrue(frames.getLast() instanceof MuxFrame.Reset);
    assertTrue(connection.isOpen());
  }

  @Test
  void physicalWriteFailureStopsSenderAndFailsAllStreams() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream first = connection.open(httpOpen(), new TestListener());
    MuxStream second = connection.open(httpOpen(), new TestListener());
    transport.awaitWrites(1);
    transport.failNext(new IOException("disk full"));

    assertTrue(failure.await());
    assertEquals(1, transport.writeCount());
    assertEquals(1, transport.closeCount());
    assertFalse(connection.isOpen());
    assertTrue(first.opened().toCompletableFuture().isCompletedExceptionally());
    assertTrue(first.closed().toCompletableFuture().isCompletedExceptionally());
    assertTrue(second.opened().toCompletableFuture().isCompletedExceptionally());
    assertTrue(second.closed().toCompletableFuture().isCompletedExceptionally());
    assertThrows(MuxConnectionClosedException.class, () -> first.sendHttpData(new byte[0]));
    connection.fail(new IOException("again"));
    assertEquals(1, failure.count());
  }

  @Test
  void websocketFragmentsAndCloseHandshakeArePreserved() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = openReadyWs(connection, listener);
    receiveHeaders(connection, stream, 101);
    receiveData(
        connection, stream, MuxDataKind.WS_TEXT, false, "he".getBytes(StandardCharsets.UTF_8));
    receiveData(
        connection, stream, MuxDataKind.WS_TEXT, true, "llo".getBytes(StandardCharsets.UTF_8));
    CompletableFuture<Void> outbound =
        stream.sendWsData(true, true, "out".getBytes(StandardCharsets.UTF_8));
    assertTrue(outbound.isDone());
    assertEquals(2, listener.data.size());
    assertEquals(MuxDataKind.WS_TEXT, listener.data.get(0).kind());
    assertEquals(stream, listener.data.get(0).stream());
    assertEquals(
        "he".getBytes(StandardCharsets.UTF_8).length, listener.data.get(0).payload().length);
    assertEquals(512, listener.data.get(0).creditCost());
    assertTrue(stream.toString().contains("WS"));
    assertFalse(listener.data.get(0).fin());
    assertTrue(listener.data.get(1).fin());
    long streamId = stream.streamId().getAsLong();

    connection.receive(CODEC.encode(new MuxFrame.Close(streamId, new MuxClose(1000, "bye"))));
    assertEquals(1, listener.closes.size());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    List<MuxFrame.Close> closes = closeFrames(transport);
    assertEquals(1, closes.size());
    assertEquals(1000, closes.get(0).close().code());
    assertEquals("bye", closes.get(0).close().reason());
  }

  @Test
  void localClosePreservesQueuedDataBeforeClose() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream stream = connection.open(wsOpen(), new TestListener());
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 101);

    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      writes.add(stream.sendWsData(false, true, new byte[65536]));
    }
    CompletableFuture<Void> close = stream.sendClose(1000, "done");
    assertFalse(close.isDone());

    while (transport.pendingCount() > 0) {
      transport.completeNext();
    }
    for (CompletableFuture<Void> write : writes) {
      assertTrue(write.isDone());
    }
    assertTrue(close.isDone());
    assertFalse(stream.closed().toCompletableFuture().isDone());
    connection.receive(
        CODEC.encode(new MuxFrame.Close(stream.streamId().getAsLong(), new MuxClose(1000, "ack"))));
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertEquals(4, dataFrameCount(transport));
    assertEquals(1, closeFrames(transport).size());
    List<MuxFrame> written = frames(transport);
    int closeIndex = written.size() - 1;
    for (int i = 1; i <= 4; i++) {
      assertEquals(MuxFrameType.DATA, written.get(closeIndex - i).type());
    }
    assertEquals(MuxFrameType.CLOSE, written.get(closeIndex).type());
  }

  @Test
  void activeWsCloseWaitsForPriorDataCompletionCallback() throws Exception {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream stream = connection.open(wsOpen(), new TestListener());
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 101);

    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CompletableFuture<Void> data = stream.sendWsData(false, true, new byte[1000]);
    data.whenComplete(
        (ignored, error) -> {
          callbackEntered.countDown();
          await(releaseCallback);
        });
    CompletableFuture<Void> close = stream.sendClose(1000, "done");
    assertEquals(1, transport.pendingCount());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> completing = executor.submit(transport::completeNext);
      await(callbackEntered);
      assertFalse(close.isDone());
      assertEquals(0, closeFrames(transport).size());

      // 回调阻塞期间另一线程触发 pump，CLOSE 仍不得越过该 DATA 回调。
      connection.receive(CODEC.encode(new MuxFrame.Window(0, 1000)));
      assertEquals(MuxFrameType.DATA, frames(transport).getLast().type());
      assertEquals(0, closeFrames(transport).size());

      releaseCallback.countDown();
      completing.get(3, TimeUnit.SECONDS);
    } finally {
      releaseCallback.countDown();
      executor.shutdownNow();
    }

    transport.awaitWrites(3);
    List<MuxFrame> written = frames(transport);
    assertEquals(MuxFrameType.DATA, written.get(written.size() - 2).type());
    assertEquals(MuxFrameType.CLOSE, written.getLast().type());
    transport.completeNext();
    assertTrue(close.isDone());
  }

  @Test
  void websocketCloseTimeoutResetsOnlyThatStream() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection =
        new MuxConnection(transport, new RecordingFailure(), Duration.ofMillis(30));
    TestListener listener = new TestListener();
    MuxStream stream = openReadyWs(connection, listener);
    receiveHeaders(connection, stream, 101);
    stream.sendClose(1000, "local");
    assertTrue(awaitFuture(stream.closed().toCompletableFuture()));
    List<MuxFrame.Reset> resets = resetFrames(transport);
    assertEquals(1, resets.size());
    assertEquals("WS_CLOSE_TIMEOUT", resets.get(0).reset().code());
    assertTrue(connection.isOpen());
  }

  @Test
  void websocketOutboundFragmentsStayWithinOneTypedMessage() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    MuxStream stream = openReadyWs(connection, new TestListener());

    assertThrows(IllegalStateException.class, () -> stream.sendWsData(true, false, new byte[] {1}));
    assertThrows(IllegalStateException.class, () -> stream.sendClose(1000, "too early"));

    receiveHeaders(connection, stream, 101);
    stream.sendWsData(true, false, new byte[] {1});
    assertThrows(
        IllegalArgumentException.class, () -> stream.sendWsData(false, true, new byte[] {2}));
    stream.sendWsData(true, true, new byte[] {2});
    stream.sendWsData(false, true, new byte[] {3});

    List<MuxFrame.Data> data = dataFrames(transport);
    assertEquals(3, data.size());
    assertEquals(MuxDataKind.WS_BINARY, data.get(2).kind());
  }

  @Test
  void peerCloseDropsOnlyUnsentDataAndCompletesTheHandshake() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    TestListener listener = new TestListener();
    MuxStream stream = connection.open(wsOpen(), listener);
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 101);

    List<CompletableFuture<Void>> writes = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      writes.add(stream.sendWsData(false, true, new byte[65536]));
    }
    assertFalse(writes.get(0).isDone());

    connection.receive(
        CODEC.encode(
            new MuxFrame.Close(stream.streamId().getAsLong(), new MuxClose(1000, "done"))));
    for (int i = 1; i < writes.size(); i++) {
      assertTrue(writes.get(i).isCompletedExceptionally());
    }
    assertEquals(1, listener.closes.size());

    while (transport.pendingCount() > 0) {
      transport.completeNext();
    }
    assertTrue(writes.get(0).isDone());
    assertTrue(stream.closed().toCompletableFuture().isDone());
    assertEquals(1, dataFrameCount(transport));
    assertEquals(1, closeFrames(transport).size());
  }

  @Test
  void websocketFragmentTypeChangeFailsConnection() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream stream = openReadyWs(connection, new TestListener());
    receiveHeaders(connection, stream, 101);
    receiveData(connection, stream, MuxDataKind.WS_TEXT, false, new byte[] {1});
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, stream, MuxDataKind.WS_BINARY, true, new byte[] {2}));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
  }

  @Test
  void managementQueueOverflowFailsStopInsteadOfAccumulatingAcks() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    TestListener listener = new TestListener();
    listener.autoConsume = true;
    MuxStream stream = connection.open(httpOpen(), listener);
    transport.completeNext();
    ready(connection, stream);
    receiveHeaders(connection, stream, 200);
    for (int i = 0; i < 128; i++) {
      receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[0]);
    }
    assertTrue(connection.isOpen());
    assertEquals(255, connection.managementFrames());
    assertThrows(
        MuxProtocolException.class,
        () -> receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[0]));
    assertTrue(failure.await());
    assertEquals(0, connection.managementFrames());
    assertEquals(1, transport.closeCount());
  }

  @Test
  void dataWaitQueueIsBoundedAndCancellationFreesCapacity() {
    FakeMuxTransport transport = new FakeMuxTransport(false);
    MuxConnection connection = connection(transport);
    MuxStream stream = connection.open(httpOpen(), new TestListener());
    transport.completeNext();
    List<CompletableFuture<Void>> queued = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      queued.add(stream.sendHttpData(new byte[65536]));
    }
    assertThrows(MuxBackpressureException.class, () -> stream.sendHttpData(new byte[65536]));
    queued.get(0).cancel(false);
    CompletableFuture<Void> replacement = stream.sendHttpData(new byte[65536]);
    assertFalse(replacement.isDone());
    assertEquals(1, transport.writeCount());
  }

  @Test
  void streamSlotAndIdAreReusedOnlyAsNewMonotonicId() {
    FakeMuxTransport transport = new FakeMuxTransport();
    MuxConnection connection = connection(transport);
    List<MuxStream> streams = new ArrayList<>();
    for (int i = 0; i < MuxLimits.MAX_STREAMS; i++) {
      streams.add(connection.open(httpOpen(), new TestListener()));
    }
    assertThrows(
        MuxBackpressureException.class, () -> connection.open(httpOpen(), new TestListener()));
    assertEquals(64, connection.activeStreamCount());
    assertEquals(64L, streams.get(63).streamId().getAsLong());
    assertTrue(streams.get(0).reset("CANCELLED", "capacity").isDone());
    assertEquals(63, connection.activeStreamCount());
    MuxStream replacement = connection.open(httpOpen(), new TestListener());
    assertEquals(65L, replacement.streamId().getAsLong());
    assertEquals(64, connection.activeStreamCount());
  }

  @Test
  void neverAllocatedFramesAndServerRoleViolationsFailStop() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    assertThrows(
        MuxProtocolException.class, () -> connection.receive(CODEC.encode(new MuxFrame.Ready(1))));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());

    FakeMuxTransport secondTransport = new FakeMuxTransport();
    RecordingFailure secondFailure = new RecordingFailure();
    MuxConnection second = new MuxConnection(secondTransport, secondFailure);
    assertThrows(
        MuxProtocolException.class,
        () -> second.receive(CODEC.encode(new MuxFrame.Open(1, httpOpen()))));
    assertTrue(secondFailure.await());

    FakeMuxTransport thirdTransport = new FakeMuxTransport();
    RecordingFailure thirdFailure = new RecordingFailure();
    MuxConnection third = new MuxConnection(thirdTransport, thirdFailure);
    assertThrows(
        MuxProtocolException.class, () -> third.receive(CODEC.encode(new MuxFrame.Complete(1))));
    assertTrue(thirdFailure.await());
  }

  @Test
  void forgedWindowsFailStopButClosedStreamWindowIsIgnored() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream stream = openReadyHttp(connection, new TestListener());
    stream.sendHttpData(new byte[1000]);
    assertThrows(
        MuxProtocolException.class,
        () ->
            connection.receive(
                CODEC.encode(new MuxFrame.Window(stream.streamId().getAsLong(), 1001))));
    assertTrue(failure.await());

    FakeMuxTransport secondTransport = new FakeMuxTransport();
    RecordingFailure secondFailure = new RecordingFailure();
    MuxConnection second = new MuxConnection(secondTransport, secondFailure);
    assertThrows(
        MuxProtocolException.class, () -> second.receive(CODEC.encode(new MuxFrame.Window(0, 1))));
    assertTrue(secondFailure.await());
  }

  @Test
  void localCloseAndExplicitFailConvergeWithoutRetry() {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream stream = connection.open(httpOpen(), new TestListener());
    connection.close();
    assertFalse(connection.isOpen());
    assertTrue(transport.isClosed());
    assertTrue(stream.closed().toCompletableFuture().isCompletedExceptionally());
    assertTrue(connection.failure().isPresent());
    assertEquals(0, failure.count());

    FakeMuxTransport secondTransport = new FakeMuxTransport();
    RecordingFailure secondFailure = new RecordingFailure();
    MuxConnection second = new MuxConnection(secondTransport, secondFailure);
    MuxStream secondStream = second.open(httpOpen(), new TestListener());
    second.fail(new IOException("socket lost"));
    assertTrue(secondFailure.await());
    assertTrue(secondStream.closed().toCompletableFuture().isCompletedExceptionally());
    assertTrue(secondTransport.isClosed());
  }

  @Test
  void localArgumentGuardsAndMalformedInputFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MuxConnection(new FakeMuxTransport(), new RecordingFailure(), Duration.ZERO));

    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    MuxStream http = openReadyHttp(connection, new TestListener());
    assertThrows(
        IllegalArgumentException.class,
        () -> http.sendHttpData(new byte[MuxLimits.MAX_PAYLOAD_BYTES + 1]));
    assertThrows(IllegalArgumentException.class, () -> http.sendWsData(true, true, new byte[0]));
    MuxStream ws = openReadyWs(connection, new TestListener());
    assertThrows(IllegalArgumentException.class, () -> ws.sendHttpData(new byte[0]));

    assertThrows(MuxProtocolException.class, () -> connection.receive(new byte[] {2}));
    assertTrue(failure.await());
    assertThrows(
        MuxConnectionClosedException.class, () -> connection.open(httpOpen(), new TestListener()));
    assertThrows(
        MuxConnectionClosedException.class,
        () -> connection.receive(CODEC.encode(new MuxFrame.Ready(1))));
  }

  @Test
  void duplicateAndOutOfOrderStreamFramesFailStop() {
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          ready(connection, stream);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          receiveHeaders(connection, stream, 200);
          receiveHeaders(connection, stream, 200);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          receiveHeaders(connection, stream, 200);
          receiveEnd(connection, stream);
          receiveEnd(connection, stream);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          receiveHeaders(connection, stream, 200);
          receiveEnd(connection, stream);
          receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1]);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyWs(connection, new TestListener());
          receiveHeaders(connection, stream, 200);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyWs(connection, new TestListener());
          receiveHeaders(connection, stream, 101);
          receiveEnd(connection, stream);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          receiveHeaders(connection, stream, 200);
          connection.receive(
              CODEC.encode(
                  new MuxFrame.Close(stream.streamId().getAsLong(), new MuxClose(1000, "not ws"))));
        });
  }

  @Test
  void headersAndDataBeforeReadyFailStop() {
    assertProtocolFailure(
        connection -> {
          MuxStream stream = connection.open(httpOpen(), new TestListener());
          receiveHeaders(connection, stream, 200);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyHttp(connection, new TestListener());
          receiveData(connection, stream, MuxDataKind.HTTP, false, new byte[1]);
        });
    assertProtocolFailure(
        connection -> {
          MuxStream stream = openReadyWs(connection, new TestListener());
          receiveData(connection, stream, MuxDataKind.WS_TEXT, true, new byte[1]);
        });
  }

  private static MuxConnection connection(FakeMuxTransport transport) {
    return new MuxConnection(transport, new RecordingFailure());
  }

  private static void assertProtocolFailure(Consumer<MuxConnection> action) {
    FakeMuxTransport transport = new FakeMuxTransport();
    RecordingFailure failure = new RecordingFailure();
    MuxConnection connection = new MuxConnection(transport, failure);
    assertThrows(MuxProtocolException.class, () -> action.accept(connection));
    assertTrue(failure.await());
    assertFalse(connection.isOpen());
  }

  private static MuxOpen httpOpen() {
    return MuxOpen.http(
        VIEW_ID, 7, "GET", "/api/resource", List.of(new MuxHeader("Accept", "text/plain")));
  }

  private static MuxOpen wsOpen() {
    return MuxOpen.webSocket(VIEW_ID, 7, "/ws", List.of(new MuxHeader("X-Test", "1")));
  }

  private static MuxStream openReadyHttp(MuxConnection connection, TestListener listener) {
    MuxStream stream = connection.open(httpOpen(), listener);
    ready(connection, stream);
    return stream;
  }

  private static MuxStream openReadyWs(MuxConnection connection, TestListener listener) {
    MuxStream stream = connection.open(wsOpen(), listener);
    ready(connection, stream);
    return stream;
  }

  private static void ready(MuxConnection connection, MuxStream stream) {
    connection.receive(CODEC.encode(new MuxFrame.Ready(stream.streamId().getAsLong())));
  }

  private static void receiveHeaders(MuxConnection connection, MuxStream stream, int status) {
    connection.receive(
        CODEC.encode(
            new MuxFrame.Headers(
                stream.streamId().getAsLong(),
                new MuxHeaders(status, List.of(new MuxHeader("Content-Type", "text/plain"))))));
  }

  private static void receiveData(
      MuxConnection connection, MuxStream stream, MuxDataKind kind, boolean fin, byte[] payload) {
    receiveData(connection, stream.streamId().getAsLong(), kind, fin, payload);
  }

  private static void receiveData(
      MuxConnection connection, long streamId, MuxDataKind kind, boolean fin, byte[] payload) {
    connection.receive(CODEC.encode(new MuxFrame.Data(streamId, kind, fin, payload)));
  }

  private static void receiveEnd(MuxConnection connection, MuxStream stream) {
    connection.receive(CODEC.encode(new MuxFrame.End(stream.streamId().getAsLong())));
  }

  private static List<MuxFrame> frames(FakeMuxTransport transport) {
    List<MuxFrame> frames = new ArrayList<>();
    for (byte[] wire : transport.written()) {
      frames.add(CODEC.decode(wire));
    }
    return frames;
  }

  private static int dataFrameCount(FakeMuxTransport transport) {
    return (int) frames(transport).stream().filter(MuxFrame.Data.class::isInstance).count();
  }

  private static int windowFrameCount(FakeMuxTransport transport) {
    return windows(transport).size();
  }

  private static List<MuxFrame.Data> dataFrames(FakeMuxTransport transport) {
    List<MuxFrame.Data> data = new ArrayList<>();
    for (MuxFrame frame : frames(transport)) {
      if (frame instanceof MuxFrame.Data frameData) {
        data.add(frameData);
      }
    }
    return data;
  }

  private static List<MuxFrame.Window> windows(FakeMuxTransport transport) {
    List<MuxFrame.Window> windows = new ArrayList<>();
    for (MuxFrame frame : frames(transport)) {
      if (frame instanceof MuxFrame.Window window) {
        windows.add(window);
      }
    }
    return windows;
  }

  private static List<MuxFrame.Close> closeFrames(FakeMuxTransport transport) {
    List<MuxFrame.Close> closes = new ArrayList<>();
    for (MuxFrame frame : frames(transport)) {
      if (frame instanceof MuxFrame.Close close) {
        closes.add(close);
      }
    }
    return closes;
  }

  private static List<MuxFrame.Reset> resetFrames(FakeMuxTransport transport) {
    List<MuxFrame.Reset> resets = new ArrayList<>();
    for (MuxFrame frame : frames(transport)) {
      if (frame instanceof MuxFrame.Reset reset) {
        resets.add(reset);
      }
    }
    return resets;
  }

  private static boolean awaitFuture(CompletableFuture<?> future) {
    try {
      future.get(3, TimeUnit.SECONDS);
      return true;
    } catch (Exception error) {
      return false;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) {
        throw new AssertionError("latch was not released");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", error);
    }
  }

  private static final class TestListener implements MuxStreamListener {

    final List<MuxStream> readies = new ArrayList<>();
    final List<MuxHeaders> headers = new ArrayList<>();
    final List<MuxReceivedData> data = new ArrayList<>();
    final List<MuxReset> resets = new ArrayList<>();
    final List<MuxClose> closes = new ArrayList<>();
    final CountDownLatch resetLatch = new CountDownLatch(1);
    final CountDownLatch closeLatch = new CountDownLatch(1);
    boolean autoConsume;

    @Override
    public void onReady(MuxStream stream) {
      readies.add(stream);
    }

    @Override
    public void onHeaders(MuxStream stream, MuxHeaders headers) {
      this.headers.add(headers);
    }

    @Override
    public void onData(MuxStream stream, MuxReceivedData data) {
      this.data.add(data);
      if (autoConsume) {
        data.consumed();
      }
    }

    @Override
    public void onReset(MuxStream stream, MuxReset reset) {
      resets.add(reset);
      resetLatch.countDown();
    }

    @Override
    public void onClose(MuxStream stream, MuxClose close) {
      closes.add(close);
      closeLatch.countDown();
    }
  }

  private static final class RecordingFailure implements MuxConnectionListener {

    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);
    int count;

    @Override
    public void onFailure(MuxConnection connection, Throwable cause) {
      failure.compareAndSet(null, cause);
      count++;
      latch.countDown();
    }

    boolean await() {
      try {
        return latch.await(3, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    int count() {
      return count;
    }
  }
}
