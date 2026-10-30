package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Async;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** ApplicationEventWebSocketHandler：帧路由、ack 先行、幂等、资源错误保活、backpressure 与断线释放。 */
class ApplicationEventWebSocketHandlerTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID EVENT_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID IDE_ENVIRONMENT =
      UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID IDE_DOCUMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID IDE_VIEW = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID IDE_CLAIM = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY =
      new ResourceKey(ResourceKind.CANVAS, new UUID(0L, 2L));
  private static final int DEFAULT_SENDER_CAPACITY = 512;

  // 测试 fixture 显式提供事件软策略：只注入发送队列容量以驱动 backpressure，
  // 其余维度取仓库默认值（与 SystemSettings.Advanced 默认一致）。
  private static final long TEST_MAX_BYTES = 2L * 1024 * 1024;
  private static final long TEST_SEND_TIMEOUT_MILLIS = 10_000L;
  private static final long TEST_HEARTBEAT_INTERVAL_MILLIS = 20_000L;

  private ThreadVersionEventSource threadVersionSource;
  private RealtimeEventSource realtimeSource;
  private RealtimeSubscriptionService realtimeSubscriptions;
  private CanvasVersionEventSource canvasVersionSource;
  private ProjectInvalidationHub projectInvalidationHub;
  private ApplicationEventHub hub;
  private IdeBrowserUseRegistry browserUseRegistry;
  private ApplicationEventWebSocketHandler handler;
  private WebSocketSession springSession;
  private SentRecorder recorder;
  private AtomicLong nowNanos;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    threadVersionSource = mock(ThreadVersionEventSource.class);
    realtimeSource = mock(RealtimeEventSource.class);
    realtimeSubscriptions = mock(RealtimeSubscriptionService.class);
    canvasVersionSource = mock(CanvasVersionEventSource.class);
    projectInvalidationHub = mock(ProjectInvalidationHub.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, () -> {}));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn((AutoCloseable) () -> {});
    when(canvasVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(3L, () -> {}));
    when(projectInvalidationHub.subscribe(any(), any())).thenReturn(() -> {});
    hub =
        new ApplicationEventHub(
            threadVersionSource,
            realtimeSource,
            realtimeSubscriptions,
            canvasVersionSource,
            projectInvalidationHub,
            new ExecutionTreeChangeHub(),
            new InteractionChangeHub(),
            new EnvironmentChangeHub(),
            512);
    browserUseRegistry = new IdeBrowserUseRegistry();
    nowNanos = new AtomicLong(1_000_000_000L);
    rebuildHandler(DEFAULT_SENDER_CAPACITY);
  }

  /** 用给定 sender 容量重建 handler 与 recorder mock（容量小可驱动 backpressure 路径）。 */
  private void rebuildHandler(int capacity) throws Exception {
    handler =
        new ApplicationEventWebSocketHandler(
            hub,
            new EventFrameCodec(new RealtimeEventJsonCodec()),
            new ApplicationEventSettings(
                capacity, TEST_MAX_BYTES, TEST_SEND_TIMEOUT_MILLIS, TEST_HEARTBEAT_INTERVAL_MILLIS),
            EVENT_NODE,
            browserUseRegistry,
            null,
            nowNanos::get);
    recorder = new SentRecorder();
    Session jakartaSession = mock(Session.class);
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              String text = inv.getArgument(0);
              SendHandler sendHandler = inv.getArgument(1);
              if (text.contains("\"type\":\"connected\"")) {
                // connected 是最先发出的帧；测试自动完成它，后续断言仍以订阅/IDE 帧起点。
                recorder.connectedFrame = text;
                sendHandler.onResult(new SendResult());
                return null;
              }
              recorder.sent.add(text);
              recorder.handlers.add(sendHandler);
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    doAnswer(
            inv -> {
              recorder.pings.add(inv.getArgument(0));
              return null;
            })
        .when(async)
        .sendPing(any(ByteBuffer.class));
    when(jakartaSession.getAsyncRemote()).thenReturn(async);
    springSession =
        mock(WebSocketSession.class, withSettings().extraInterfaces(NativeWebSocketSession.class));
    when(springSession.getId()).thenReturn("session-1");
    when(((NativeWebSocketSession) springSession).getNativeSession()).thenReturn(jakartaSession);
  }

  @Test
  void connectionStartsWithV2IdentityFrame() throws Exception {
    handler.afterConnectionEstablished(springSession);

    JsonNode connected = JsonMapper.builder().build().readTree(recorder.connectedFrame);
    assertEquals(2, connected.path("version").asInt());
    assertEquals("connected", connected.path("type").asText());
    assertEquals(EVENT_NODE.toString(), connected.path("eventNodeId").asText());
    UUID connectionId = UUID.fromString(connected.path("connectionId").asText());
    assertTrue(recorder.sent.isEmpty(), "connected must be the first logical frame");
    assertFalse(
        browserUseRegistry.usage(new ConnectionIdentity(EVENT_NODE, connectionId)).isPresent());
  }

  @Test
  void ideUseAcceptsIdenticalRevisionAndAtomicallyReplacesHigherRevision() throws Exception {
    handler.afterConnectionEstablished(springSession);
    ConnectionIdentity identity = connectedIdentity();

    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "1", IDE_CLAIM, 5L)));
    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(1L, browserUseRegistry.usage(identity).orElseThrow().revision());

    // 同 revision 同内容幂等 ack，不改变登记。
    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "1", IDE_CLAIM, 5L)));
    assertEquals(
        "{\"version\":2,\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());

    // 同 revision 不同内容拒绝，但连接保持可用。
    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "1", UUID.randomUUID(), 5L)));
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"IDE_USE_REJECTED\",\"message\":\"ide-use revision must increase and keep identical content for the same revision\"}",
        recorder.sent.get(2));
    verify(springSession, never()).close(any());
    recorder.handlers.get(2).onResult(new SendResult());

    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "2", IDE_CLAIM, 6L)));
    assertEquals(
        "{\"version\":2,\"type\":\"ide-use-accepted\",\"revision\":\"2\"}", recorder.sent.get(3));
    recorder.handlers.get(3).onResult(new SendResult());
    IdeBrowserUse accepted = browserUseRegistry.usage(identity).orElseThrow();
    assertEquals(2L, accepted.revision());
    assertEquals(6L, accepted.views().getFirst().bindingEpoch());

    // 已固定的 document 不能在同一物理连接上改变。
    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(UUID.randomUUID(), "3", IDE_CLAIM, 7L)));
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"IDE_USE_REJECTED\",\"message\":\"documentId is fixed for this connection\"}",
        recorder.sent.get(4));
  }

  @Test
  void backpressureRevokesUsageBeforeTheErrorFrameIsSent() throws Exception {
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);
    ConnectionIdentity identity = connectedIdentity();
    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "1", IDE_CLAIM, 5L)));
    assertTrue(browserUseRegistry.usage(identity).isPresent());

    // ack 在途占满唯一槽位：heartbeat 入队失败应立即撤销使用证明，而不是等物理 close 回调。
    handler.heartbeat();
    assertFalse(
        browserUseRegistry.usage(identity).isPresent(),
        "failed connection must stop providing usage before the physical close");

    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());
  }

  @Test
  void nativePingPongTimeoutClosesAndReleasesTheConnectionGeneration() throws Exception {
    handler.afterConnectionEstablished(springSession);
    ConnectionIdentity identity = connectedIdentity();
    handler.handleTextMessage(
        springSession, textMessage(ideUseFrame(IDE_DOCUMENT, "1", IDE_CLAIM, 5L)));
    recorder.handlers.getFirst().onResult(new SendResult());
    assertTrue(browserUseRegistry.usage(identity).isPresent());

    long start = nowNanos.get();
    long second = TimeUnit.SECONDS.toNanos(15);
    handler.ping(start + second);
    assertEquals(1, recorder.pings.size());
    nowNanos.set(start + second);
    handler.handlePongMessage(springSession, new PongMessage());

    handler.ping(start + 2 * second);
    assertEquals(2, recorder.pings.size());
    handler.ping(start + 3 * second);
    assertEquals(3, recorder.pings.size());
    verify(springSession, never()).close(any());

    handler.ping(start + 4 * second);
    assertFalse(browserUseRegistry.usage(identity).isPresent());
    ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reason.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reason.getValue().getCloseCode());
  }

  @Test
  void pingIsQueuedBehindInFlightTextAndSharesTheBoundedQueue() throws Exception {
    rebuildHandler(2);
    handler.afterConnectionEstablished(springSession);

    handler.heartbeat(); // 在途文本
    handler.ping();
    assertEquals(1, recorder.sent.size());
    assertTrue(recorder.pings.isEmpty(), "ping must not overtake in-flight text");

    recorder.handlers.getFirst().onResult(new SendResult());
    assertEquals(1, recorder.pings.size());

    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);
    handler.heartbeat(); // 占满唯一槽位
    handler.ping(); // 入队失败，进入 backpressure 关闭路径
    assertEquals(1, recorder.sent.size());
    assertTrue(recorder.pings.isEmpty());

    recorder.handlers.getFirst().onResult(new SendResult());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(1));
  }

  @Test
  void subscribeEmitsAckThenBufferedEvents() {
    AtomicReference<Consumer<ThreadVersionEventSource.Event>> versionConsumer =
        new AtomicReference<>();
    when(threadVersionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              versionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    // ack 帧已入队在途（sendText 被 recorder 捕获、尚未完成），此时到达的事件必须排队在 ack 之后。
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("6", false));
    assertEquals(1, recorder.sent.size(), "event must wait for the ack frame to be sent");

    recorder.handlers.get(0).onResult(new SendResult()); // 完成 ack 发送，事件帧随后串行发出
    assertEquals(2, recorder.sent.size(), "ack frame must precede the buffered event frame");
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        recorder.sent.get(0));
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"version\",\"cursor\":\"6\",\"data\":{\"version\":\"6\"}}",
        recorder.sent.get(1));
  }

  @Test
  void duplicateSubscribeIsIdempotent() {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    verify(threadVersionSource, times(1)).subscribe(any(), any());
    verify(realtimeSource, times(1)).subscribe(any(), any(), any());
  }

  @Test
  void unsubscribeReleasesTheSubscription() throws Exception {
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, versionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(springSession, textMessage(unsubscribeFrame(THREAD)));

    verify(versionHandle).close();
    verify(realtimeHandle).close();
  }

  @Test
  void invalidFrameSendsErrorThenClosesWithProtocolError() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage("{\"op\":\"subscribe\"}"));

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.version must be the integer 2\"}",
        recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify(springSession, never()).close(any());
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.PROTOCOL_ERROR, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void unknownResourceSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    when(threadVersionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread: " + THREAD));
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession(), never())
        .close(any());

    // 连接保持：后续合法帧仍被处理。
    doReturn(new SourceSubscribed(5L, () -> {})).when(threadVersionSource).subscribe(any(), any());
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        recorder.sent.get(1));
  }

  @Test
  void resyncCallbackSendsResyncFrame() throws Exception {
    AtomicReference<Runnable> resync = new AtomicReference<>();
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              resync.set(inv.getArgument(2));
              return (AutoCloseable) () -> {};
            });
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    resync.get().run();
    assertEquals(1, recorder.sent.size(), "resync frame must wait behind the in-flight ack");
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"resync\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        recorder.sent.get(1));
  }

  @Test
  void heartbeatUsesTheExistingAsyncQueueAndStopsAfterConnectionClose() {
    handler.afterConnectionEstablished(springSession);

    handler.heartbeat();

    assertEquals(List.of("{\"version\":2,\"type\":\"heartbeat\"}"), recorder.sent);
    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);
    handler.heartbeat();
    assertEquals(1, recorder.sent.size());
  }

  @Test
  void heartbeatBackpressureClosesTheConnection() throws Exception {
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);

    handler.heartbeat(); // heartbeat 在途，占满容量
    handler.heartbeat(); // 入队失败，error 排在在途 heartbeat 后

    assertEquals(List.of("{\"version\":2,\"type\":\"heartbeat\"}"), recorder.sent);
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void ackEnqueueFailureClosesFreshSubscriptionWithoutKeepingIt() throws Exception {
    AutoCloseable versionHandle1 = mock(AutoCloseable.class);
    AutoCloseable versionHandle2 = mock(AutoCloseable.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, versionHandle1));
    when(canvasVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(3L, versionHandle2));
    // 容量 1：第一个订阅的 ack 在途后，第二个订阅的 ack 必然入队失败。
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(
        springSession,
        textMessage(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                + CANVAS_KEY.id()
                + "\"}}"));

    // 第二个订阅的 ack 入队失败：刚建订阅必须释放（不 activate、不留在订阅表），并回 BACKPRESSURE 后关闭。
    verify(versionHandle2).close();
    assertEquals(1, recorder.sent.size(), "error frame must wait behind the in-flight ack");
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());

    // fail 已关闭本代际：迟到 subscribe 不得重新建立上游。
    verify(canvasVersionSource, times(1)).subscribe(any(), any());
  }

  @Test
  void eventEnqueueFailureTriggersBackpressure() throws Exception {
    AtomicReference<Consumer<ThreadVersionEventSource.Event>> versionConsumer =
        new AtomicReference<>();
    when(threadVersionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              versionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    // 容量 2：ack 完成后，事件 6 在途、7 排队、8 入队成功、9 入队失败 → BACKPRESSURE。
    rebuildHandler(2);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    recorder.handlers.get(0).onResult(new SendResult()); // ack 完成
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("6", false));
    recorder.handlers.get(1).onResult(new SendResult()); // 事件 6 完成
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("7", false)); // 在途
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("8", false)); // 排队
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("9", false)); // 溢出

    assertEquals(3, recorder.sent.size(), "error frame must wait behind the in-flight event");
    recorder.handlers.get(2).onResult(new SendResult()); // 事件 7 完成
    assertEquals(4, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(3));
    recorder.handlers.get(3).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void disconnectReleasesAllSubscriptions() throws Exception {
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, versionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);

    verify(versionHandle).close();
    verify(realtimeHandle).close();
  }

  @Test
  void shutdownClosesLiveConnectionsWithServiceRestartAndReleasesSubscriptions() throws Exception {
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, versionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    handler.shutdown();

    // 订阅立即释放；error 帧排在在途 ack 之后作为最后一帧，出队后以 1012 关闭会话。
    verify(versionHandle).close();
    verify(realtimeHandle).close();
    assertEquals(1, recorder.sent.size(), "error frame must wait behind the in-flight ack");
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"SEND_FAILED\",\"message\":\"event channel is shutting down\"}",
        recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.SERVICE_RESTART, reasonCaptor.getValue().getCloseCode());

    // 幂等：再次 shutdown 与后续帧均安全。
    handler.shutdown();
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    verify(threadVersionSource, times(1)).subscribe(any(), any());
  }

  @Test
  void subscribeAfterConnectionCloseIsIgnored() {
    handler.afterConnectionEstablished(springSession);
    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    // 关闭后的帧不得再登记订阅或建立上游。
    verify(threadVersionSource, never()).subscribe(any(), any());
  }

  @Test
  void connectionCloseDuringSubscribeWaitsForTheInFlightSubscribe() throws Exception {
    // subscribe 卡在 hub 建立上游时关闭连接。closeLock 让建立、登记与关闭互斥：
    // close 必须等待 in-flight subscribe 完成，再关闭新登记的订阅。
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    CountDownLatch establishing = new CountDownLatch(1);
    CountDownLatch establishDone = new CountDownLatch(1);
    CountDownLatch closeTaskStarted = new CountDownLatch(1);
    CountDownLatch closeDone = new CountDownLatch(1);
    doAnswer(
            inv -> {
              establishing.countDown();
              establishDone.await();
              return new SourceSubscribed(5L, versionHandle);
            })
        .when(threadVersionSource)
        .subscribe(any(), any());
    handler.afterConnectionEstablished(springSession);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> frame =
          executor.submit(
              () -> handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD))));
      assertTrue(establishing.await(5, TimeUnit.SECONDS), "subscribe must reach hub establish");
      Future<?> close =
          executor.submit(
              () -> {
                closeTaskStarted.countDown();
                handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);
                closeDone.countDown();
                return null;
              });
      assertTrue(closeTaskStarted.await(5, TimeUnit.SECONDS), "close task must start");
      // establish 未放行时，close 必须等待 in-flight subscribe 退出 closeLock。
      assertFalse(
          closeDone.await(2, TimeUnit.SECONDS), "close must wait for the in-flight subscribe");

      establishDone.countDown(); // 放行建立：subscribe 登记完成后 close 才关闭它
      close.get(5, TimeUnit.SECONDS);
      frame.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    verify(versionHandle).close(); // close 后不得遗留 hub 订阅。
  }

  @Test
  void concurrentDuplicateSubscribeRegistersOnlyOneSubscription() throws Exception {
    // 第二个帧在第一个订阅 ack 入队后、登记前到达。closeLock 串行两个帧，
    // 第二个帧只能看到已登记状态并幂等返回，不能建立或覆盖另一份订阅。
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    when(threadVersionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, versionHandle));
    CountDownLatch ackEnqueued = new CountDownLatch(1);
    CountDownLatch ackRelease = new CountDownLatch(1);
    CountDownLatch secondTaskStarted = new CountDownLatch(1);
    Async async =
        ((Session) ((NativeWebSocketSession) springSession).getNativeSession()).getAsyncRemote();
    doAnswer(
            inv -> {
              String text = inv.getArgument(0);
              SendHandler sendHandler = inv.getArgument(1);
              if (text.contains("\"type\":\"connected\"")) {
                recorder.connectedFrame = text;
                sendHandler.onResult(new SendResult());
                return null;
              }
              ackEnqueued.countDown();
              ackRelease.await();
              recorder.sent.add(text);
              recorder.handlers.add(sendHandler);
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    handler.afterConnectionEstablished(springSession);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(
              () -> handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD))));
      assertTrue(ackEnqueued.await(5, TimeUnit.SECONDS), "first subscribe must reach ack enqueue");
      Future<?> second =
          executor.submit(
              () -> {
                secondTaskStarted.countDown();
                handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
              });
      assertTrue(secondTaskStarted.await(5, TimeUnit.SECONDS), "second frame task must start");
      ackRelease.countDown(); // 放行第一个订阅登记
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    verify(threadVersionSource, times(1)).subscribe(any(), any()); // 重复帧只建立一次上游
    handler.handleTextMessage(springSession, textMessage(unsubscribeFrame(THREAD)));
    verify(versionHandle).close(); // 只登记了一个订阅：unsubscribe 后上游必须释放
  }

  private static String subscribeFrame(UUID threadId) {
    return "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"}}";
  }

  private ConnectionIdentity connectedIdentity() throws Exception {
    JsonNode connected = JsonMapper.builder().build().readTree(recorder.connectedFrame);
    return new ConnectionIdentity(
        EVENT_NODE, UUID.fromString(connected.path("connectionId").asText()));
  }

  private static String ideUseFrame(
      UUID documentId, String revision, UUID claimId, long bindingEpoch) {
    return """
        {"version":2,"type":"ide-use","documentId":"%s","revision":"%s","views":[
          {"environmentId":"%s","viewId":"%s","claimId":"%s","bindingEpoch":"%d"}]}
        """
        .formatted(documentId, revision, IDE_ENVIRONMENT, IDE_VIEW, claimId, bindingEpoch);
  }

  private static String unsubscribeFrame(UUID threadId) {
    return "{\"version\":2,\"type\":\"unsubscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"}}";
  }

  private static TextMessage textMessage(String payload) {
    return new TextMessage(payload);
  }

  private static final class SentRecorder {
    private final List<String> sent = new ArrayList<>();
    private final List<SendHandler> handlers = new ArrayList<>();
    private final List<ByteBuffer> pings = new ArrayList<>();
    private String connectedFrame;
  }
}
