package fun.fengwk.kkstudio.ide.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonDataBind;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.ide.mux.MuxDataKind;
import fun.fengwk.kkstudio.ide.mux.MuxFrame;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxReceivedData;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;
import fun.fengwk.kkstudio.ide.mux.MuxTransport;
import fun.fengwk.kkstudio.ide.mux.MuxWireCodec;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图：一个 Environment 只在有效 READY owner lease 下下发一次
 * DATA_BIND，接受后复用同一物理连接打开多条逻辑流；旧票据、旧代际回调和租约失效都不能影响新连接。
 */
class IdeDataConnectionRegistryTest {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENVIRONMENT, "daemon-instance-1");
  private static final UUID VIEW = UUID.fromString("2a4e6b6e-8f1c-4d8a-9c2d-5b7e1f3a9c01");
  private static final MuxWireCodec CODEC = new MuxWireCodec();

  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void shutdownWorkers() {
    workers.shutdownNow();
  }

  @Test
  void issuesOneTimeDataBindAndOpensLogicalStreamsOnOneConnection() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    AtomicReference<DaemonDataBind> bind = new AtomicReference<>();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              bind.set(value);
              IdeDataConnection accepted =
                  registry.accept(ENVIRONMENT, value.ticket(), transport).orElseThrow();
              assertEquals(value.generation(), accepted.generation());
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    RecordingListener firstListener = new RecordingListener();
    CompletableFuture<MuxStream> first =
        CompletableFuture.supplyAsync(
            () ->
                registry.openHttp(
                    ENVIRONMENT, VIEW, 7L, "GET", "/index.html", List.of(), firstListener),
            workers);
    MuxStream firstStream = first.get(5, TimeUnit.SECONDS);
    transport.awaitWrites(1);

    MuxFrame.Open firstOpen =
        assertInstanceOf(MuxFrame.Open.class, CODEC.decode(transport.writes().get(0)));
    assertEquals(VIEW, firstOpen.request().viewId());
    assertEquals(7L, firstOpen.request().bindingEpoch());
    assertEquals("GET", firstOpen.request().method());
    assertEquals("/index.html", firstOpen.request().path());

    DaemonDataBind issued = bind.get();
    assertTrue(issued.generation() > 0);
    assertTrue(issued.ticket().matches("[A-Za-z0-9_-]{43}"));
    assertTrue(issued.streamUrl().toString().contains("?ticket=" + issued.ticket()));
    assertEquals(1, registry.activeStreamCount(ENVIRONMENT));

    // 同一连接继续开第二条流，不需要第二次 DATA_BIND。
    RecordingListener secondListener = new RecordingListener();
    MuxStream second =
        registry.openHttp(
            ENVIRONMENT,
            UUID.randomUUID(),
            8L,
            "POST",
            "/api/save?x=1",
            List.of(new MuxHeader("accept", "text/plain")),
            secondListener);
    transport.awaitWrites(2);
    assertEquals(2, registry.activeStreamCount(ENVIRONMENT));
    assertFalse(firstStream.streamId().isEmpty());
    assertFalse(second.streamId().isEmpty());
    assertNotEquals(firstStream.streamId().getAsLong(), second.streamId().getAsLong());

    // Daemon 侧按 wire 顺序驱动第一条流；DATA 消费后才归还 credit。
    IdeDataConnection connection = registry.activeConnection(ENVIRONMENT).orElseThrow();
    long streamId = firstStream.streamId().getAsLong();
    connection.receive(CODEC.encode(new MuxFrame.Ready(streamId)));
    connection.receive(
        CODEC.encode(
            new MuxFrame.Headers(
                streamId,
                MuxHeaders.http(200, List.of(new MuxHeader("content-type", "text/plain"))))));
    connection.receive(
        CODEC.encode(new MuxFrame.Data(streamId, MuxDataKind.HTTP, false, new byte[] {1, 2, 3})));
    connection.receive(CODEC.encode(new MuxFrame.End(streamId)));
    assertTrue(firstListener.end.await(2, TimeUnit.SECONDS));
    assertEquals(1, firstListener.data.size());
    registry.close();
  }

  @Test
  void ticketIsSingleUseAndBoundToCurrentControlIdentity() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    AtomicReference<DaemonDataBind> bind = new AtomicReference<>();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              bind.set(invocation.getArgument(1));
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    CompletableFuture<MuxStream> ignored =
        CompletableFuture.supplyAsync(
            () ->
                registry.openHttp(
                    ENVIRONMENT, VIEW, 1L, "GET", "/", List.of(), new RecordingListener()),
            workers);
    awaitBind(bind);
    DaemonDataBind issued = bind.get();

    // 不是当前 READY 控制身份：一次性 ticket 被拒绝，且不会误绑旧连接。
    when(server.connectionIdentity(ENVIRONMENT))
        .thenReturn(Optional.of(new DaemonConnectionIdentity(ENVIRONMENT, "daemon-instance-2")));
    assertTrue(registry.accept(ENVIRONMENT, issued.ticket(), transport).isEmpty());

    // 恢复原身份后，同一 ticket 已被消费，不能再使用。
    when(server.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(IDENTITY));
    assertTrue(registry.accept(ENVIRONMENT, issued.ticket(), transport).isEmpty());
    ExecutionException timeout =
        assertThrows(ExecutionException.class, () -> ignored.get(5, TimeUnit.SECONDS));
    assertInstanceOf(IdeDataConnectTimeoutException.class, timeout.getCause());
    registry.close();
  }

  @Test
  void leaseIsRecheckedForEveryAdmissionAndFencesTheOldConnection() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              registry.accept(ENVIRONMENT, value.ticket(), transport).orElseThrow();
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    MuxStream stream =
        registry.openHttp(ENVIRONMENT, VIEW, 1L, "GET", "/", List.of(), new RecordingListener());
    assertTrue(stream.isOpen());

    when(server.holdsReadyLease(ENVIRONMENT)).thenReturn(false);
    assertThrows(
        IdeDataUnavailableException.class,
        () ->
            registry.openHttp(
                ENVIRONMENT, VIEW, 1L, "GET", "/next", List.of(), new RecordingListener()));
    assertTrue(registry.activeConnection(ENVIRONMENT).isEmpty());
    assertTrue(transport.isClosed());
    assertFalse(stream.isOpen());
    registry.close();
  }

  @Test
  void staleViewAuthorizationCannotOpenOnTheCurrentConnection() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              registry.accept(ENVIRONMENT, value.ticket(), transport).orElseThrow();
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    registry.openHttp(ENVIRONMENT, VIEW, 1L, "GET", "/", List.of(), new RecordingListener());
    DaemonConnectionIdentity oldIdentity =
        new DaemonConnectionIdentity(ENVIRONMENT, "daemon-instance-old");
    assertThrows(
        IdeDataGenerationReplacedException.class,
        () ->
            registry.openHttp(
                ENVIRONMENT,
                VIEW,
                1L,
                "GET",
                "/stale",
                List.of(),
                oldIdentity,
                new RecordingListener()));
    assertTrue(registry.activeConnection(ENVIRONMENT).isPresent());
    assertEquals(1, registry.activeStreamCount(ENVIRONMENT));
    assertFalse(transport.isClosed());
    registry.close();
  }

  @Test
  void staleGenerationFailureCannotClearTheNewConnection() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    AtomicReference<IdeDataConnection> current = new AtomicReference<>();
    AtomicReference<RecordingTransport> transport = new AtomicReference<>(new RecordingTransport());
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              current.set(
                  registry.accept(ENVIRONMENT, value.ticket(), transport.get()).orElseThrow());
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    registry.openHttp(ENVIRONMENT, VIEW, 1L, "GET", "/one", List.of(), new RecordingListener());
    IdeDataConnection old = current.get();
    old.fail(new IllegalStateException("physical disconnect"));
    assertTrue(registry.activeConnection(ENVIRONMENT).isEmpty());

    // 重连使用新代际与新 transport。
    transport.set(new RecordingTransport());
    registry.openHttp(ENVIRONMENT, VIEW, 1L, "GET", "/two", List.of(), new RecordingListener());
    IdeDataConnection newest = current.get();
    assertTrue(newest.generation() > old.generation());
    assertEquals(newest, registry.activeConnection(ENVIRONMENT).orElseThrow());

    // 旧代际的迟到失败不能清除新连接。
    old.fail(new IllegalStateException("late old callback"));
    assertEquals(newest, registry.activeConnection(ENVIRONMENT).orElseThrow());
    assertTrue(newest.isOpen());
    registry.close();
  }

  @Test
  void connectTimeoutRevokesThePendingTicket() {
    EnvironmentDaemonServer server = readyServer();
    IdeDataConnectionRegistry registry =
        new IdeDataConnectionRegistry(
            server,
            (environmentId, generation, ticket) ->
                URI.create(
                    "http://127.0.0.1:8080/api/harness/environment-data/v2/"
                        + environmentId
                        + "?ticket="
                        + ticket),
            new SecureRandom(),
            Clock.systemUTC(),
            Duration.ofSeconds(30),
            Duration.ofMillis(80),
            Duration.ofSeconds(30),
            null);
    AtomicReference<DaemonDataBind> bind = new AtomicReference<>();
    doAnswer(
            invocation -> {
              bind.set(invocation.getArgument(1));
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    assertThrows(
        IdeDataConnectTimeoutException.class,
        () ->
            registry.openHttp(
                ENVIRONMENT, VIEW, 1L, "GET", "/", List.of(), new RecordingListener()));
    assertTrue(
        registry.accept(ENVIRONMENT, bind.get().ticket(), new RecordingTransport()).isEmpty());
    registry.close();
  }

  @Test
  void resetViewOnlyResetsMatchingViewAndEpoch() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              registry.accept(ENVIRONMENT, value.ticket(), transport).orElseThrow();
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    MuxStream target =
        registry.openHttp(ENVIRONMENT, VIEW, 3L, "GET", "/a", List.of(), new RecordingListener());
    MuxStream otherEpoch =
        registry.openHttp(ENVIRONMENT, VIEW, 4L, "GET", "/b", List.of(), new RecordingListener());
    MuxStream otherView =
        registry.openHttp(
            ENVIRONMENT, UUID.randomUUID(), 3L, "GET", "/c", List.of(), new RecordingListener());
    assertEquals(1, registry.resetView(ENVIRONMENT, VIEW, 3L));
    assertFalse(target.isOpen());
    assertTrue(otherEpoch.isOpen());
    assertTrue(otherView.isOpen());
    registry.close();
  }

  @Test
  void closeGenerationClosesActiveConnectionAndRejectsItsTicket() throws Exception {
    EnvironmentDaemonServer server = readyServer();
    RecordingTransport transport = new RecordingTransport();
    IdeDataConnectionRegistry registry = registry(server, Duration.ofSeconds(2));
    doAnswer(
            invocation -> {
              DaemonDataBind value = invocation.getArgument(1);
              registry.accept(ENVIRONMENT, value.ticket(), transport).orElseThrow();
              return null;
            })
        .when(server)
        .sendDataBind(eq(ENVIRONMENT), any(DaemonDataBind.class));

    registry.openHttp(ENVIRONMENT, VIEW, 1L, "GET", "/", List.of(), new RecordingListener());
    registry.closeGeneration(IDENTITY);
    assertTrue(registry.activeConnection(ENVIRONMENT).isEmpty());
    assertTrue(transport.isClosed());
    registry.close();
  }

  private static EnvironmentDaemonServer readyServer() {
    EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);
    when(server.connectionIdentity(ENVIRONMENT)).thenReturn(Optional.of(IDENTITY));
    when(server.holdsReadyLease(ENVIRONMENT)).thenReturn(true);
    return server;
  }

  private static IdeDataConnectionRegistry registry(
      EnvironmentDaemonServer server, Duration connectTimeout) {
    return new IdeDataConnectionRegistry(
        server,
        (environmentId, generation, ticket) ->
            URI.create(
                "http://127.0.0.1:8080/api/harness/environment-data/v2/"
                    + environmentId
                    + "?ticket="
                    + ticket),
        new SecureRandom(),
        Clock.systemUTC(),
        Duration.ofSeconds(30),
        connectTimeout,
        Duration.ofSeconds(30),
        null);
  }

  private static void awaitBind(AtomicReference<DaemonDataBind> bind) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (bind.get() == null) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("DATA_BIND was not sent");
      }
      Thread.sleep(5L);
    }
  }

  private static final class RecordingListener implements MuxStreamListener {

    private final List<MuxReceivedData> data = new ArrayList<>();
    private final CountDownLatch end = new CountDownLatch(1);

    @Override
    public void onData(MuxStream stream, MuxReceivedData received) {
      synchronized (data) {
        data.add(received);
      }
      received.consumed();
    }

    @Override
    public void onEnd(MuxStream stream) {
      end.countDown();
    }
  }

  private static final class RecordingTransport implements MuxTransport {

    private final Object monitor = new Object();
    private final List<byte[]> written = new ArrayList<>();
    private boolean closed;

    @Override
    public CompletionStage<Void> write(byte[] frame) {
      synchronized (monitor) {
        if (closed) {
          throw new IllegalStateException("transport is closed");
        }
        written.add(frame.clone());
        monitor.notifyAll();
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      synchronized (monitor) {
        closed = true;
        monitor.notifyAll();
      }
    }

    private List<byte[]> writes() {
      synchronized (monitor) {
        return new ArrayList<>(written);
      }
    }

    private void awaitWrites(int count) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      synchronized (monitor) {
        while (written.size() < count) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0L) {
            throw new AssertionError("timed out waiting for physical writes");
          }
          try {
            monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
          }
        }
      }
    }

    private boolean isClosed() {
      synchronized (monitor) {
        return closed;
      }
    }
  }
}
