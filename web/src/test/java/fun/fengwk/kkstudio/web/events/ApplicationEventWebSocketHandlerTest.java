package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** ApplicationEventWebSocketHandler：唯一 carrier 载体上的 v2 帧路由、ack 先行、幂等、资源错误保活、backpressure 与断线释放。 */
class ApplicationEventWebSocketHandlerTest {

  private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000e0");
  private static final UUID ENVIRONMENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final String SESSION_ID = "session-1";
  private static final UUID THREAD = new UUID(0L, 1L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY =
      new ResourceKey(ResourceKind.CANVAS, new UUID(0L, 2L));
  private static final ApplicationEventSettings SETTINGS =
      new ApplicationEventSettings(512, 8L * 1024 * 1024, 10_000L, 20_000L);

  private ThreadVersionEventSource threadVersionSource;
  private RealtimeEventSource realtimeSource;
  private CanvasVersionEventSource canvasVersionSource;
  private ProjectInvalidationHub projectInvalidationHub;
  private ApplicationEventHub hub;
  private ShellGateway gateway;
  private ShellGateway.Connection shellConnection;
  private ManualExecutorService sendExecutor;
  private ScheduledExecutorService heartbeatScheduler;
  private NotificationLimits limits;
  private ApplicationEventWebSocketHandler handler;
  private WebSocketSession springSession;
  private SentRecorder recorder;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    threadVersionSource = mock(ThreadVersionEventSource.class);
    realtimeSource = mock(RealtimeEventSource.class);
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
            canvasVersionSource,
            projectInvalidationHub,
            new ExecutionTreeChangeHub(),
            new InteractionChangeHub(),
            new EnvironmentChangeHub(),
            512);
    gateway = mock(ShellGateway.class);
    shellConnection = mock(ShellGateway.Connection.class);
    ArgumentCaptor<Consumer<TerminalEvent>> sinkCaptor = ArgumentCaptor.forClass(Consumer.class);
    ArgumentCaptor<Runnable> resyncCaptor = ArgumentCaptor.forClass(Runnable.class);
    when(gateway.open(eq(SESSION_ID), sinkCaptor.capture(), resyncCaptor.capture()))
        .thenReturn(shellConnection);
    rebuildHandler(512);
  }

  @AfterEach
  void tearDown() {
    heartbeatScheduler.shutdownNow();
    sendExecutor.shutdownNow();
  }

  /** 用给定逻辑包容量重建 handler 与 recorder mock（容量小可驱动 backpressure 路径）。 */
  private void rebuildHandler(int queueCapacity) {
    if (heartbeatScheduler != null) {
      heartbeatScheduler.shutdownNow();
    }
    NotificationLimits defaults = NotificationLimits.defaults();
    limits =
        new NotificationLimits(
            defaults.maxMessageBytes(),
            (int) SETTINGS.maxBytes(),
            queueCapacity,
            defaults.reassemblyBytes(),
            defaults.reassemblyMessages(),
            defaults.reassemblyTimeout(),
            defaults.sendBatchFrames());
    sendExecutor = new ManualExecutorService();
    heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    handler =
        new ApplicationEventWebSocketHandler(
            hub,
            gateway,
            new EventFrameCodec(new RealtimeEventJsonCodec()),
            sendExecutor,
            heartbeatScheduler,
            limits,
            Math.toIntExact(SETTINGS.sendTimeoutMillis()),
            SETTINGS.heartbeatIntervalMillis());
    recorder = new SentRecorder();
    Session jakartaSession = mock(Session.class);
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              recorder.sent.add(inv.getArgument(0));
              // 物理帧同步完成：驱动唯一在途发送链推进，等价于真实 AsyncRemote 回调。
              ((SendHandler) inv.getArgument(1)).onResult(new SendResult());
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    when(jakartaSession.getAsyncRemote()).thenReturn(async);
    springSession =
        mock(WebSocketSession.class, withSettings().extraInterfaces(NativeWebSocketSession.class));
    when(springSession.getId()).thenReturn(SESSION_ID);
    when(((NativeWebSocketSession) springSession).getNativeSession()).thenReturn(jakartaSession);
  }

  @Test
  void subscribeEmitsAckThenBufferedEvent() {
    AtomicReference<Consumer<ThreadVersionEventSource.Event>> versionConsumer =
        new AtomicReference<>();
    when(threadVersionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              versionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    // 事件在 ack 尚未 drain 时到达，必须排在 ack 之后。
    versionConsumer.get().accept(new ThreadVersionEventSource.Event("6", false));
    sendExecutor.runAll();

    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        body(recorder.sent.get(0)));
    assertEquals(
        "{\"version\":2,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"version\",\"cursor\":\"6\",\"data\":{\"version\":\"6\"}}",
        body(recorder.sent.get(1)));
  }

  @Test
  void duplicateSubscribeIsIdempotent() {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));

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

    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    handler.handleTextMessage(springSession, textMessage(clientFrame(unsubscribeFrame(THREAD))));

    verify(versionHandle).close();
    verify(realtimeHandle).close();
  }

  @Test
  void invalidFrameSendsErrorThenClosesWithProtocolError() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame("{\"op\":\"subscribe\"}")));
    sendExecutor.runAll();

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.version must be the integer 2\"}",
        body(recorder.sent.get(0)));
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.PROTOCOL_ERROR, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void versionOneFrameIsRejected() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(
        springSession,
        textMessage(
            clientFrame(
                "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + THREAD
                    + "\"}}")));
    sendExecutor.runAll();

    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.version must be the integer 2\"}",
        body(recorder.sent.get(0)));
  }

  @Test
  void unknownResourceSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    when(threadVersionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread: " + THREAD));
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    sendExecutor.runAll();

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        body(recorder.sent.get(0)));
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession(), never())
        .close(any());

    // 连接保持：后续合法帧仍被处理。
    doReturn(new SourceSubscribed(5L, () -> {})).when(threadVersionSource).subscribe(any(), any());
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    sendExecutor.runAll();
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        body(recorder.sent.get(1)));
  }

  @Test
  void resyncCallbackSendsResyncFrame() {
    AtomicReference<Runnable> resync = new AtomicReference<>();
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              resync.set(inv.getArgument(2));
              return (AutoCloseable) () -> {};
            });
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));

    resync.get().run();
    sendExecutor.runAll();
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":2,\"type\":\"resync\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        body(recorder.sent.get(1)));
  }

  @Test
  void heartbeatUsesTheExistingAsyncQueueAndStopsAfterConnectionClose() {
    handler.afterConnectionEstablished(springSession);

    handler.heartbeat();
    sendExecutor.runAll();
    assertEquals(List.of("{\"version\":2,\"type\":\"heartbeat\"}"), bodies());

    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);
    sendExecutor.runAll();
    handler.heartbeat();
    sendExecutor.runAll();
    assertEquals(
        List.of("{\"version\":2,\"type\":\"heartbeat\"}"),
        bodies(),
        "closed connection must not accept further frames");
  }

  /** 逻辑包容量占满后新入队被拒：error 帧也无法进入同一预算，只能放弃在途逻辑包并以 1013 直接关闭，绝不存在无预算 error 旁路。 */
  @Test
  void heartbeatBackpressureAbortsAndClosesWithTryAgainLater() throws Exception {
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);

    handler.heartbeat(); // 占满唯一逻辑包容量
    handler.heartbeat(); // 入队失败 → error 帧同样容不下 → 直接放弃关闭
    sendExecutor.runAll();

    assertEquals(
        List.of(), bodies(), "an over-budget error frame must not bypass the shared budget");
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
    // 容量 1：第一个订阅的 ack 占满容量后，第二个订阅的 ack 必然入队失败。
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    handler.handleTextMessage(
        springSession,
        textMessage(
            clientFrame(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                    + CANVAS_KEY.id()
                    + "\"}}")));

    // 第二个订阅的 ack 入队失败：刚建订阅必须释放（不 activate、不留在订阅表）；error 帧同样容不下 → 直接关闭。
    verify(versionHandle2).close();
    sendExecutor.runAll();
    assertEquals(
        List.of(), bodies(), "an over-budget error frame must not bypass the shared budget");
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void shellCommandFrameIsRoutedToGateway() {
    handler.afterConnectionEstablished(springSession);
    TerminalCommand command =
        new TerminalCommand(UUID.randomUUID(), ENVIRONMENT, VIEWER, new TerminalCommand.Open(null));
    String frame =
        "{\"version\":2,\"type\":\"shell.command\",\"command\":"
            + new TerminalControlCodec().encodeCommand(command)
            + "}";

    handler.handleTextMessage(springSession, textMessage(clientFrame(frame)));

    ArgumentCaptor<TerminalCommand> captor = ArgumentCaptor.forClass(TerminalCommand.class);
    verify(shellConnection).receive(captor.capture());
    assertEquals(command, captor.getValue());
    sendExecutor.runAll();
    assertEquals(List.of(), bodies(), "a command alone must not emit any frame");
  }

  @Test
  void shellEventFromGatewayIsEncodedAsShellEventFrame() {
    handler.afterConnectionEstablished(springSession);
    Consumer<TerminalEvent> sink = capturedShellSink();
    TerminalEvent event =
        new TerminalEvent(
            UUID.randomUUID(),
            ENVIRONMENT,
            VIEWER,
            null,
            new TerminalEvent.ErrorPayload(
                ErrorCode.TERMINAL_NOT_FOUND, ErrorDisposition.NOT_EXECUTED));

    sink.accept(event);
    sendExecutor.runAll();

    assertEquals(1, recorder.sent.size());
    String decoded = body(recorder.sent.get(0));
    assertTrue(decoded.startsWith("{\"version\":2,\"type\":\"shell.event\",\"event\":"));
    assertTrue(decoded.contains(ENVIRONMENT.toString()));
  }

  /** Bus resync 必须以 1012 SERVICE_RESTART 关闭真实连接并清理观察：只清缓存却让连接一直 open 违反恢复契约；重复 resync 幂等。 */
  @Test
  void shellResyncClosesRealConnectionWithServiceRestart() throws Exception {
    handler.afterConnectionEstablished(springSession);
    Runnable resync = capturedShellResync();

    resync.run();
    sendExecutor.runAll();
    resync.run(); // 幂等：已关闭连接上的重复 resync 无副作用

    assertEquals(
        List.of(
            "{\"version\":2,\"type\":\"error\",\"code\":\"SEND_FAILED\",\"message\":\"event channel is shutting down\"}"),
        bodies());
    verify(shellConnection).close();
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.SERVICE_RESTART, reasonCaptor.getValue().getCloseCode());
  }

  /** 每个物理浏览器连接使用独立随机 endpoint publisher，绝不复用 nodeInstanceId。 */
  @Test
  void distinctConnectionsUseDistinctEndpointPublishers() {
    handler.afterConnectionEstablished(springSession);
    WebSocketSession second =
        mock(WebSocketSession.class, withSettings().extraInterfaces(NativeWebSocketSession.class));
    when(second.getId()).thenReturn("session-2");
    Session secondNative = mock(Session.class);
    Async secondAsync = mock(Async.class);
    doAnswer(
            inv -> {
              recorder.sent.add(inv.getArgument(0));
              ((SendHandler) inv.getArgument(1)).onResult(new SendResult());
              return null;
            })
        .when(secondAsync)
        .sendText(any(String.class), any(SendHandler.class));
    when(secondNative.getAsyncRemote()).thenReturn(secondAsync);
    when(((NativeWebSocketSession) second).getNativeSession()).thenReturn(secondNative);
    when(gateway.open(eq("session-2"), any(), any()))
        .thenReturn(mock(ShellGateway.Connection.class));

    handler.afterConnectionEstablished(second);
    handler.heartbeat();
    sendExecutor.runAll();

    assertEquals(2, recorder.sent.size());
    UUID first = NotificationCarrier.decode(recorder.sent.get(0), limits, CLIENT).publisher();
    UUID other = NotificationCarrier.decode(recorder.sent.get(1), limits, CLIENT).publisher();
    assertNotEquals(SELF, first, "must not reuse the node instance id as publisher");
    assertNotEquals(SELF, other, "must not reuse the node instance id as publisher");
    assertNotEquals(first, other, "each physical connection owns its own publisher");
  }

  @Test
  void connectionCloseReleasesShellObservationAndSendChain() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    sendExecutor.runAll();
    recorder.sent.clear();

    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);
    sendExecutor.runAll();

    verify(shellConnection).close();
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession()).close(any());
  }

  @Test
  void nonCarrierFrameClosesConnection() throws Exception {
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage("{\"version\":2,\"type\":\"heartbeat\"}"));
    sendExecutor.runAll();

    verify((WebSocketSession) springSession).close(any(CloseStatus.class));
    assertTrue(
        recorder.sent.isEmpty(), "a carrier violation is closed without echoing any payload");
  }

  @Test
  void binaryFrameClosesConnection() throws Exception {
    handler.afterConnectionEstablished(springSession);

    handler.handleBinaryMessage(springSession, new BinaryMessage(new byte[0]));
    sendExecutor.runAll();

    ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
    verify(springSession).close(statusCaptor.capture());
    assertEquals(
        CloseReason.CloseCodes.PROTOCOL_ERROR.getCode(), statusCaptor.getValue().getCode());
  }

  @Test
  void transportErrorClosesConnection() throws Exception {
    handler.afterConnectionEstablished(springSession);

    handler.handleTransportError(springSession, new IllegalStateException("transport down"));
    sendExecutor.runAll();

    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession()).close(any());
  }

  @Test
  void nonNativeSessionIsRejected() {
    assertThrows(
        IllegalStateException.class,
        () -> handler.afterConnectionEstablished(mock(WebSocketSession.class)));
  }

  @Test
  void constructorRejectsInvalidLimitsAndIntervals() {
    EventFrameCodec codec = new EventFrameCodec(new RealtimeEventJsonCodec());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationEventWebSocketHandler(
                hub, gateway, codec, sendExecutor, heartbeatScheduler, limits, 0, 20_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationEventWebSocketHandler(
                hub, gateway, codec, sendExecutor, heartbeatScheduler, limits, 10_000, 0L));
    // @Autowired 构造：applicationEventMaxBytes 超过支持范围必须拒绝，绝不静默抬高。
    ApplicationEventSettings tooLarge =
        new ApplicationEventSettings(1, (long) Integer.MAX_VALUE + 1, 1L, 1L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationEventWebSocketHandler(
                hub, gateway, codec, tooLarge, sendExecutor, heartbeatScheduler));
  }

  @Test
  void shutdownFlushesServiceRestartAndReleasesShellObservation() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));

    handler.shutdown();
    sendExecutor.runAll();
    handler.shutdown(); // 幂等：已收尾的连接第二次 terminate 直接返回

    assertEquals(
        List.of(
            "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + THREAD
                + "\"},\"cursor\":\"5\"}",
            "{\"version\":2,\"type\":\"error\",\"code\":\"SEND_FAILED\",\"message\":\"event channel is shutting down\"}"),
        bodies(),
        "shutdown must drain queued packets before the restart error frame");
    verify(shellConnection).close();
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.SERVICE_RESTART, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void resourceResyncEnqueueFailureTriggersBackpressure() throws Exception {
    AtomicReference<Runnable> resync = new AtomicReference<>();
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              resync.set(inv.getArgument(2));
              return (AutoCloseable) () -> {};
            });
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));

    resync.get().run(); // 容量已占满，resync 帧入队失败；error 帧同样容不下 → 直接关闭
    sendExecutor.runAll();

    assertEquals(
        List.of(), bodies(), "an over-budget error frame must not bypass the shared budget");
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void shellEventEnqueueFailureTriggersBackpressure() throws Exception {
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);
    handler.heartbeat(); // 占满唯一逻辑包容量
    TerminalEvent event =
        new TerminalEvent(
            UUID.randomUUID(),
            ENVIRONMENT,
            VIEWER,
            null,
            new TerminalEvent.ErrorPayload(
                ErrorCode.TERMINAL_NOT_FOUND, ErrorDisposition.NOT_EXECUTED));

    capturedShellSink().accept(event);
    sendExecutor.runAll();

    assertEquals(
        List.of(), bodies(), "an over-budget error frame must not bypass the shared budget");
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void autowiredConstructorDerivesLimitsFromSettingsAndShutsDownIdempotently() {
    EventFrameCodec codec = new EventFrameCodec(new RealtimeEventJsonCodec());
    ApplicationEventSettings settings =
        new ApplicationEventSettings(4, 8L * 1024 * 1024, 1_000L, 1_000L);

    ApplicationEventWebSocketHandler built =
        new ApplicationEventWebSocketHandler(
            hub, gateway, codec, settings, sendExecutor, heartbeatScheduler);
    assertNotNull(built);

    built.shutdown();
    built.shutdown(); // 幂等：第二次 terminate 直接返回
  }

  @Test
  void closeNativeToleratesCloseFailure() throws Exception {
    doThrow(new IllegalStateException("already gone"))
        .when(springSession)
        .close(any(CloseStatus.class));
    handler.afterConnectionEstablished(springSession);

    handler.handleBinaryMessage(springSession, new BinaryMessage(new byte[0]));
    sendExecutor.runAll();

    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession()).close(any());
  }

  @Test
  void resourceNotFoundEnqueueFailureTriggersBackpressure() throws Exception {
    when(threadVersionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread"));
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);
    handler.heartbeat(); // 占满唯一逻辑包容量

    handler.handleTextMessage(springSession, textMessage(clientFrame(subscribeFrame(THREAD))));
    sendExecutor.runAll();

    assertEquals(
        List.of(), bodies(), "an over-budget error frame must not bypass the shared budget");
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @SuppressWarnings("unchecked")
  private Consumer<TerminalEvent> capturedShellSink() {
    ArgumentCaptor<Consumer<TerminalEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
    verify(gateway).open(eq(SESSION_ID), captor.capture(), any());
    return captor.getValue();
  }

  private Runnable capturedShellResync() {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(gateway).open(eq(SESSION_ID), any(), captor.capture());
    return captor.getValue();
  }

  private static String subscribeFrame(UUID thread) {
    return "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + thread
        + "\"}}";
  }

  private static String unsubscribeFrame(UUID thread) {
    return "{\"version\":2,\"type\":\"unsubscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + thread
        + "\"}}";
  }

  private static TextMessage textMessage(String payload) {
    return new TextMessage(payload);
  }

  /** 把逻辑正文包成唯一物理 carrier（publisher=浏览器、target=广播、固定 topic）。 */
  private static String clientFrame(String logicalBody) {
    byte[] bytes = logicalBody.getBytes(StandardCharsets.UTF_8);
    return new NotificationCarrier(
            CLIENT,
            null,
            ApplicationEventWebSocketHandler.CARRIER_TOPIC,
            UUID.randomUUID(),
            0,
            NotificationCarrier.count(bytes.length),
            bytes.length,
            bytes)
        .encode();
  }

  /** 解出出站 carrier 的逻辑正文（用浏览器身份解码，避免 own-echo 丢弃）。 */
  private String body(String rawFrame) {
    NotificationCarrier carrier = NotificationCarrier.decode(rawFrame, limits, CLIENT);
    assertNotNull(carrier);
    assertEquals(1, carrier.count());
    return new String(carrier.bytes(), StandardCharsets.UTF_8);
  }

  private List<String> bodies() {
    List<String> result = new ArrayList<>();
    for (String frame : recorder.sent) {
      result.add(body(frame));
    }
    return result;
  }

  private static final class SentRecorder {
    private final List<String> sent = new ArrayList<>();
  }

  /** 手动 ExecutorService：把 drain/close 任务排队，测试显式运行，保证发送链确定性。 */
  private static final class ManualExecutorService extends AbstractExecutorService {
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> pending = new ArrayList<>(tasks);
      tasks.clear();
      return pending;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return tasks.isEmpty();
    }

    private void runAll() {
      while (!tasks.isEmpty()) {
        tasks.poll().run();
      }
    }
  }
}
