package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonEndpoint;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Environment Daemon handler 的连接装配、压缩门禁、carrier 入站桥接与幂等解绑契约。 */
class EnvironmentDaemonWebSocketHandlerTest {

  private final ScheduledThreadPoolExecutor timer =
      EnvironmentDaemonWebSocketConfiguration.environmentDaemonSendDeadlineTimer();

  @AfterEach
  void shutdownTimer() {
    timer.shutdownNow();
  }

  private static final WebSocketExtension PERMESSAGE_DEFLATE =
      new WebSocketExtension("permessage-deflate");

  @Test
  void exposesTheHarnessEnvironmentDaemonPath() {
    assertEquals("/api/harness/environment-daemon/v1", EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** carrier 入站重组为完整逻辑体后投递；关闭事件先且只向 Gateway 解绑一次。 */
  @Test
  void forwardsInboundAndDisconnectsGatewayExactlyOnce() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    WebSocketSession session = deflateSession("connection-id");
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<DaemonChannel> connectionCaptor = ArgumentCaptor.forClass(DaemonChannel.class);
    verify(endpoint).open(connectionCaptor.capture());
    assertEquals("connection-id", connectionCaptor.getValue().connectionId());

    TestPeer client = new TestPeer(UUID.randomUUID());
    for (String frame : client.fragments("inbound")) {
      handler.handleTextMessage(session, new TextMessage(frame));
    }
    verify(endpoint).receive("connection-id", "inbound");
    handler.handleTransportError(session, new IllegalStateException("transport failed"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    verify(endpoint, times(1)).close(eq("connection-id"));
  }

  /** 新连接把 JSR-356 文本/二进制缓冲设为固定 carrier 物理上限。 */
  @Test
  void appliesPhysicalCarrierLimitOnEachConnection() {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    NativeWebSocketSession session = mock(NativeWebSocketSession.class);
    Session jsrSession = mock(Session.class);
    when(session.getId()).thenReturn("connection-id");
    when(session.isOpen()).thenReturn(true);
    when(session.getExtensions()).thenReturn(List.of(PERMESSAGE_DEFLATE));
    when(session.getNativeSession(Session.class)).thenReturn(jsrSession);

    handler.afterConnectionEstablished(session);
    verify(session).setTextMessageSizeLimit(NotificationCarrier.PAYLOAD_LIMIT);
    verify(session).setBinaryMessageSizeLimit(NotificationCarrier.PAYLOAD_LIMIT);
    verify(jsrSession).setMaxTextMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
    verify(jsrSession).setMaxBinaryMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
  }

  /** 未协商 permessage-deflate 时：以 1010 关闭、不向会话核心开放通道、不套用消息缓冲。 */
  @Test
  void rejectsConnectionWithoutPermessageDeflate() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("connection-id");
    when(session.isOpen()).thenReturn(true);
    when(session.getExtensions()).thenReturn(List.of(new WebSocketExtension("x-custom")));

    handler.afterConnectionEstablished(session);

    verify(session).close(CloseStatus.REQUIRED_EXTENSION);
    verify(endpoint, never()).open(any());
    verify(session, never()).setTextMessageSizeLimit(any(Integer.class));
  }

  /** 扩展声明缺失时同样拒绝，避免把「未协商」误判为「已协商」。 */
  @Test
  void rejectsConnectionWhenExtensionsAreUnavailable() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("connection-id");
    when(session.isOpen()).thenReturn(true);
    when(session.getExtensions()).thenReturn(null);

    handler.afterConnectionEstablished(session);

    verify(session).close(CloseStatus.REQUIRED_EXTENSION);
    verify(endpoint, never()).open(any());
  }

  /** 通道把本地容量拒绝原样表达为 BUSY，不回传为连接失效。 */
  @Test
  void mapsBusyOfferResultWithoutClosingChannel() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    WebSocketSession session = deflateSession("connection-id");
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<DaemonChannel> connectionCaptor = ArgumentCaptor.forClass(DaemonChannel.class);
    verify(endpoint).open(connectionCaptor.capture());

    DaemonChannel connection = connectionCaptor.getValue();
    assertEquals(DaemonOfferResult.ACCEPTED, connection.offerText("first"));
    connection.closeAfterFlush();
    assertEquals(DaemonOfferResult.CLOSED, connection.offerText("after-fence"));
  }

  /** 出站队列满时返回 BUSY 且不关闭连接。 */
  @Test
  void mapsFullQueueToBusy() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonTransportProperties properties = properties();
    properties.setQueueCapacity(1);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketSession session = deflateSession("connection-id");
    doAnswer(
            invocation -> {
              release.await();
              return null;
            })
        .when(session)
        .sendMessage(any());
    handler.afterConnectionEstablished(session);
    ArgumentCaptor<DaemonChannel> connectionCaptor = ArgumentCaptor.forClass(DaemonChannel.class);
    verify(endpoint).open(connectionCaptor.capture());

    try {
      DaemonChannel connection = connectionCaptor.getValue();
      assertEquals(DaemonOfferResult.ACCEPTED, connection.offerText("first"));
      assertEquals(DaemonOfferResult.BUSY, connection.offerText("second"));
      verify(session, never()).close(any());
    } finally {
      release.countDown();
    }
  }

  /** 非法物理帧（非 carrier）确定性关闭：向 Gateway 解绑一次并关闭 session。 */
  @Test
  void rejectsInvalidFrameAndDisconnects() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties());
    WebSocketSession session = deflateSession("connection-id");
    handler.afterConnectionEstablished(session);

    handler.handleTextMessage(session, new TextMessage("{\"messageType\":\"HELLO\"}"));

    verify(endpoint, timeout(5_000)).close(eq("connection-id"));
    verify(session, timeout(5_000)).close(any(CloseStatus.class));
  }

  /** 一个连接的 endpoint.close 阻塞（PG/lease）时，另一连接的 send deadline 仍必须在共享 timer 上裁决。 */
  @Test
  void blockedEndpointCloseDoesNotBlockAnotherConnectionsDeadline() throws Exception {
    DaemonEndpoint endpoint = mock(DaemonEndpoint.class);
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              closeEntered.countDown();
              releaseClose.await();
              return null;
            })
        .when(endpoint)
        .close(any(String.class));

    EnvironmentDaemonTransportProperties properties = properties();
    properties.setSendTimeout(Duration.ofMillis(50));
    EnvironmentDaemonWebSocketHandler handler = handler(endpoint, properties);

    try {
      // 连接 A：非法帧触发断开，endpoint.close 阻塞在虚拟线程上。
      WebSocketSession a = deflateSession("A");
      handler.afterConnectionEstablished(a);
      handler.handleTextMessage(a, new TextMessage("not-a-carrier"));
      assertTrue(closeEntered.await(5, TimeUnit.SECONDS));

      // 连接 B：send 阻塞；其 deadline 仍必须在这条共享 timer 上裁决（isOpen 转为 false）。
      WebSocketSession b = deflateSession("B");
      CountDownLatch releaseB = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                releaseB.await();
                return null;
              })
          .when(b)
          .sendMessage(any());
      handler.afterConnectionEstablished(b);
      ArgumentCaptor<DaemonChannel> channels = ArgumentCaptor.forClass(DaemonChannel.class);
      verify(endpoint, times(2)).open(channels.capture());
      DaemonChannel bChannel =
          channels.getAllValues().stream()
              .filter(channel -> channel.connectionId().equals("B"))
              .findFirst()
              .orElseThrow();
      assertEquals(DaemonOfferResult.ACCEPTED, bChannel.offerText("blocked-send"));
      await(() -> !bChannel.isOpen());
      releaseB.countDown();
    } finally {
      releaseClose.countDown();
    }
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!check.get() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(check.get(), "condition not reached before deadline");
  }

  @FunctionalInterface
  private interface Check {
    boolean get();
  }

  private EnvironmentDaemonWebSocketHandler handler(
      DaemonEndpoint endpoint, EnvironmentDaemonTransportProperties properties) {
    return new EnvironmentDaemonWebSocketHandler(endpoint, properties, timer, UUID.randomUUID());
  }

  private static EnvironmentDaemonTransportProperties properties() {
    return new EnvironmentDaemonTransportProperties();
  }

  /** 构造与端点一致的已协商压缩会话。 */
  private static WebSocketSession deflateSession(String connectionId) throws IOException {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(connectionId);
    when(session.isOpen()).thenReturn(true);
    when(session.getExtensions()).thenReturn(List.of(PERMESSAGE_DEFLATE));
    return session;
  }
}
