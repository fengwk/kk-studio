package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** ApplicationEventWebSocketHandler：帧路由、ack 先行、幂等、资源错误保活、backpressure 与断线释放。 */
class ApplicationEventWebSocketHandlerTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY =
      new ResourceKey(ResourceKind.CANVAS, new UUID(0L, 2L));

  private ThreadRevisionEventSource revisionSource;
  private RealtimeEventSource realtimeSource;
  private CanvasVersionEventSource versionSource;
  private ApplicationEventHub hub;
  private ApplicationEventWebSocketHandler handler;
  private WebSocketSession springSession;
  private SentRecorder recorder;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    revisionSource = mock(ThreadRevisionEventSource.class);
    realtimeSource = mock(RealtimeEventSource.class);
    versionSource = mock(CanvasVersionEventSource.class);
    when(revisionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(5L, () -> {}));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn((AutoCloseable) () -> {});
    when(versionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(3L, () -> {}));
    hub = new ApplicationEventHub(revisionSource, realtimeSource, versionSource);
    rebuildHandler(AsyncTextSender.DEFAULT_CAPACITY);
  }

  /** 用给定 sender 容量重建 handler 与 recorder mock（容量小可驱动 backpressure 路径）。 */
  private void rebuildHandler(int capacity) {
    handler =
        new ApplicationEventWebSocketHandler(
            hub, new EventFrameCodec(new RealtimeEventJsonCodec()), capacity);
    recorder = new SentRecorder();
    Session jakartaSession = mock(Session.class);
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              recorder.sent.add(inv.getArgument(0));
              recorder.handlers.add(inv.getArgument(1));
              return null;
            })
        .when(async)
        .sendText(any(String.class), any(SendHandler.class));
    when(jakartaSession.getAsyncRemote()).thenReturn(async);
    springSession =
        mock(WebSocketSession.class, withSettings().extraInterfaces(NativeWebSocketSession.class));
    when(springSession.getId()).thenReturn("session-1");
    when(((NativeWebSocketSession) springSession).getNativeSession()).thenReturn(jakartaSession);
  }

  @Test
  void subscribeEmitsAckThenBufferedEvents() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    // ack 帧已入队在途（sendText 被 recorder 捕获、尚未完成），此时到达的事件必须排队在 ack 之后。
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    assertEquals(1, recorder.sent.size(), "event must wait for the ack frame to be sent");

    recorder.handlers.get(0).onResult(new SendResult()); // 完成 ack 发送，事件帧随后串行发出
    assertEquals(2, recorder.sent.size(), "ack frame must precede the buffered event frame");
    assertEquals(
        "{\"version\":1,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        recorder.sent.get(0));
    assertEquals(
        "{\"version\":1,\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"revision\",\"cursor\":\"6\",\"data\":{\"revision\":\"6\"}}",
        recorder.sent.get(1));
  }

  @Test
  void duplicateSubscribeIsIdempotent() {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    verify(revisionSource, times(1)).subscribe(any(), any());
    verify(realtimeSource, times(1)).subscribe(any(), any(), any());
  }

  @Test
  void unsubscribeReleasesTheSubscription() throws Exception {
    AutoCloseable revisionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(revisionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, revisionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(springSession, textMessage(unsubscribeFrame(THREAD)));

    verify(revisionHandle).close();
    verify(realtimeHandle).close();
  }

  @Test
  void invalidFrameSendsErrorThenCloses() throws Exception {
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage("{\"op\":\"subscribe\"}"));

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame must contain exactly [version, type, resource] fields\"}",
        recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify(springSession, never()).close(any());
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void unknownResourceSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    when(revisionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread: " + THREAD));
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"unknown thread: "
            + THREAD
            + "\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession(), never())
        .close(any());

    // 连接保持：后续合法帧仍被处理。
    doReturn(new SourceSubscribed(5L, () -> {})).when(revisionSource).subscribe(any(), any());
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":1,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
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
        "{\"version\":1,\"type\":\"resync\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"}}",
        recorder.sent.get(1));
  }

  @Test
  void ackEnqueueFailureClosesFreshSubscriptionWithoutKeepingIt() throws Exception {
    AutoCloseable revisionHandle = mock(AutoCloseable.class);
    AutoCloseable versionHandle = mock(AutoCloseable.class);
    when(revisionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, revisionHandle));
    when(versionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(3L, versionHandle));
    // 容量 1：第一个订阅的 ack 在途后，第二个订阅的 ack 必然入队失败。
    rebuildHandler(1);
    handler.afterConnectionEstablished(springSession);

    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    handler.handleTextMessage(
        springSession,
        textMessage(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                + CANVAS_KEY.id()
                + "\"}}"));

    // 第二个订阅的 ack 入队失败：刚建订阅必须释放（不 activate、不留在订阅表），并回 BACKPRESSURE 后关闭。
    verify(versionHandle).close();
    assertEquals(1, recorder.sent.size(), "error frame must wait behind the in-flight ack");
    recorder.handlers.get(0).onResult(new SendResult());
    assertEquals(2, recorder.sent.size());
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(1));
    recorder.handlers.get(1).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());

    // 未 put 到订阅表：后续 subscribe 会重新走 hub（幂等语义保持正确）。
    handler.handleTextMessage(
        springSession,
        textMessage(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"canvas\",\"id\":\""
                + CANVAS_KEY.id()
                + "\"}}"));
    verify(versionSource, times(2)).subscribe(any(), any());
  }

  @Test
  void eventEnqueueFailureTriggersBackpressure() throws Exception {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    // 容量 2：ack 完成后，事件 6 在途、7 排队、8 入队成功、9 入队失败 → BACKPRESSURE。
    rebuildHandler(2);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));
    recorder.handlers.get(0).onResult(new SendResult()); // ack 完成
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    recorder.handlers.get(1).onResult(new SendResult()); // 事件 6 完成
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("7", false)); // 在途
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("8", false)); // 排队
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("9", false)); // 溢出

    assertEquals(3, recorder.sent.size(), "error frame must wait behind the in-flight event");
    recorder.handlers.get(2).onResult(new SendResult()); // 事件 7 完成
    assertEquals(4, recorder.sent.size());
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"BACKPRESSURE\",\"message\":\"event queue is full\"}",
        recorder.sent.get(3));
    recorder.handlers.get(3).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void disconnectReleasesAllSubscriptions() throws Exception {
    AutoCloseable revisionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(revisionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, revisionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);

    verify(revisionHandle).close();
    verify(realtimeHandle).close();
  }

  private static String subscribeFrame(UUID threadId) {
    return "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"}}";
  }

  private static String unsubscribeFrame(UUID threadId) {
    return "{\"version\":1,\"type\":\"unsubscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"}}";
  }

  private static TextMessage textMessage(String payload) {
    return new TextMessage(payload);
  }

  private static final class SentRecorder {
    private final List<String> sent = new ArrayList<>();
    private final List<SendHandler> handlers = new ArrayList<>();
  }
}
