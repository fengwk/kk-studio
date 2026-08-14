package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

/** ApplicationEventWebSocketHandler：帧路由、ack 先行、幂等、错误关闭与断线释放。 */
class ApplicationEventWebSocketHandlerTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);

  private ThreadRevisionEventSource revisionSource;
  private RealtimeEventSource realtimeSource;
  private CanvasVersionEventSource versionSource;
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
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(() -> {});
    when(versionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(3L, () -> {}));
    ApplicationEventHub hub =
        new ApplicationEventHub(revisionSource, realtimeSource, versionSource);
    handler =
        new ApplicationEventWebSocketHandler(
            hub, new EventFrameCodec(new RealtimeEventJsonCodec()));

    recorder = new SentRecorder();
    Session jakartaSession = mock(Session.class);
    Async async = mock(Async.class);
    doAnswer(
            inv -> {
              recorder.sent.add(inv.getArgument(0));
              SendHandler sendHandler = inv.getArgument(1);
              recorder.handlers.add(sendHandler);
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
        "{\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"cursor\":\"5\"}",
        recorder.sent.get(0));
    assertEquals(
        "{\"type\":\"event\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + THREAD
            + "\"},\"name\":\"revision\",\"revision\":\"6\"}",
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
        "{\"type\":\"error\",\"message\":\"invalid frame: frame must contain exactly [version, type, resource] fields\"}",
        recorder.sent.get(0));
    recorder.handlers.get(0).onResult(new SendResult());
    ArgumentCaptor<CloseReason> reasonCaptor = ArgumentCaptor.forClass(CloseReason.class);
    verify(springSession, never()).close(any());
    verify((Session) ((NativeWebSocketSession) springSession).getNativeSession())
        .close(reasonCaptor.capture());
    assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, reasonCaptor.getValue().getCloseCode());
  }

  @Test
  void unknownResourceSendsErrorThenCloses() throws Exception {
    when(revisionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread: " + THREAD));
    handler.afterConnectionEstablished(springSession);
    handler.handleTextMessage(springSession, textMessage(subscribeFrame(THREAD)));

    assertEquals(1, recorder.sent.size());
    assertEquals(
        "{\"type\":\"error\",\"message\":\"subscribe failed: unknown thread: " + THREAD + "\"}",
        recorder.sent.get(0));
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
