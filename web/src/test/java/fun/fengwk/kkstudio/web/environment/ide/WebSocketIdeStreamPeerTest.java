package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamBinaryFrame;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;
import fun.fengwk.kkstudio.ide.stream.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.ide.stream.IdeStreamRelay;
import fun.fengwk.kkstudio.ide.stream.IdeStreamTicketService;
import fun.fengwk.kkstudio.ide.stream.InMemoryIdeStreamTicketService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 反向流 peer 的有界入站队列与关闭唤醒契约。 */
class WebSocketIdeStreamPeerTest {

  private final WebSocketSession session = mock(WebSocketSession.class);

  /** 空闲超时不是关闭：poll 返回 null 但 closed() 仍为 false，数据面可继续等到真实 deadline。 */
  @Test
  void idleTimeoutIsNotClosed() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    assertNull(peer.poll(Duration.ofMillis(20)));
    assertFalse(peer.closed());
    assertNull(peer.poll(Duration.ZERO));
    assertFalse(peer.closed());
  }

  /** 帧数超界即拒绝入队，绝不无界缓冲。 */
  @Test
  void rejectsBeyondFrameBudget() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    for (int i = 0; i < WebSocketIdeStreamPeer.MAX_QUEUED_FRAMES; i++) {
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Text("{}")), "frame " + i + " must fit");
    }
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Text("{}")));
  }

  /** 测试意图：入站上界由 credit 推导，一个窗口内协议允许的全部数据帧、ACK 与控制帧必须都能入队，守约的对端永远不会被误断开。 */
  @Test
  void acceptsAWholeCreditWindowOfLegalTraffic() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    byte[] tinySegment = codec.encodeWsSegment(false, true, new byte[1]);
    byte[] ack = codec.encodeAck(IdeStreamCreditWindow.MIN_FRAME_CREDIT);
    for (int i = 0; i < IdeStreamCreditWindow.MAX_IN_FLIGHT_FRAMES; i++) {
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(tinySegment)), "data frame " + i);
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(ack)), "ack frame " + i);
    }
    assertTrue(peer.offer(new IdeStreamPeer.Inbound.Text("{\"type\":\"WS_READY\"}")));
    assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(codec.encodeHttpEnd())));
  }

  /** 小零载荷控制帧同样受字节预算约束：恶意 ACK/text 洪水不能无限占用内存。 */
  @Test
  void rejectsBeyondByteBudget() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    byte[] payload = new byte[4096];
    int accepted = 0;
    while (peer.offer(new IdeStreamPeer.Inbound.Binary(payload))) {
      accepted++;
      assertTrue(accepted <= WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length + 1);
    }
    assertEquals((int) (WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length), accepted);
  }

  /** 被消费的帧释放字节预算，可继续入队。 */
  @Test
  void consumingFramesReleasesBudget() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    byte[] payload = new byte[4096];
    for (int i = 0; i < WebSocketIdeStreamPeer.MAX_QUEUED_BYTES / payload.length; i++) {
      assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
    }
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
    assertEquals(
        payload.length, ((IdeStreamPeer.Inbound.Binary) peer.poll(Duration.ZERO)).frame().length);
    assertTrue(peer.offer(new IdeStreamPeer.Inbound.Binary(payload)));
  }

  /** 关闭必须立即唤醒等待者，而不是让数据面等满超时。 */
  @Test
  void closeWakesBlockedPoll() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean returnedNull = new AtomicBoolean();
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  try {
                    returnedNull.set(peer.poll(Duration.ofSeconds(30)) == null);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    peer.close();
    waiter.join(5_000);
    assertFalse(waiter.isAlive(), "close must wake the blocked poll");
    assertTrue(returnedNull.get());
    assertTrue(peer.closed());
  }

  /** 连接侧关闭同样唤醒等待者。 */
  @Test
  void transportCloseWakesBlockedPoll() throws Exception {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    peer.poll(Duration.ofSeconds(30));
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    Thread.sleep(50);
    peer.onTransportClosed(CloseStatus.GOING_AWAY);
    waiter.join(5_000);
    assertFalse(waiter.isAlive(), "transport close must wake the blocked poll");
  }

  /** 关闭后拒绝继续发送与入队。 */
  @Test
  void closedPeerRejectsSendAndOffer() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    peer.close();
    assertThrows(IllegalStateException.class, () -> peer.sendText("{}"));
    assertFalse(peer.offer(new IdeStreamPeer.Inbound.Text("{}")));
  }

  /** 发送失败即视为连接关闭（fail-closed），并记录无关闭帧的原因供数据面诊断。 */
  @Test
  void sendFailureClosesPeer() throws Exception {
    doThrow(new IOException("boom")).when(session).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    assertThrows(IllegalStateException.class, () -> peer.sendBinary(new byte[] {1}));
    assertTrue(peer.closed());
    assertEquals(CloseStatus.NO_CLOSE_FRAME.getCode(), peer.remoteCloseStatus().getCode());
    assertEquals("send failed: IOException", peer.remoteCloseStatus().getReason());
  }

  /** 测试意图：对端关闭状态只记录首个原因；本端主动关闭不伪造对端关闭状态。 */
  @Test
  void recordsOnlyTheFirstRemoteCloseStatus() {
    WebSocketIdeStreamPeer remote = new WebSocketIdeStreamPeer(session);
    remote.onTransportClosed(CloseStatus.GOING_AWAY);
    remote.onTransportClosed(CloseStatus.SERVER_ERROR);
    assertEquals(CloseStatus.GOING_AWAY, remote.remoteCloseStatus());

    WebSocketIdeStreamPeer local = new WebSocketIdeStreamPeer(session);
    local.close(CloseStatus.POLICY_VIOLATION);
    local.onTransportClosed(CloseStatus.NORMAL);
    assertNull(local.remoteCloseStatus());
  }

  /** 测试意图：可转述的应用关闭码原样保留；缺失或不能发送的关闭码统一为 1011，避免把 1006 写到另一条连接上。 */
  @Test
  void relayableKeepsSendableCodesAndReplacesTheRest() {
    assertEquals(CloseStatus.NORMAL, IdeStreamPeer.relayable(CloseStatus.NORMAL));
    CloseStatus application = new CloseStatus(4001, "app");
    assertEquals(application, IdeStreamPeer.relayable(application));
    assertEquals(IdeStreamPeer.STREAM_LOST, IdeStreamPeer.relayable(null));
    assertEquals(IdeStreamPeer.STREAM_LOST, IdeStreamPeer.relayable(CloseStatus.NO_CLOSE_FRAME));
    assertEquals(IdeStreamPeer.STREAM_LOST, IdeStreamPeer.relayable(CloseStatus.SERVICE_RESTARTED));
  }

  /** 连接侧关闭后消费 ACK 可安全丢弃（不丢已入队响应）；数据帧仍必须 fail-closed。 */
  @Test
  void acknowledgementAfterTransportCloseIsDroppedButDataStillRejected() {
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    peer.onTransportClosed(CloseStatus.GOING_AWAY);
    assertDoesNotThrow(() -> peer.sendAcknowledgement(new byte[] {3, 0, 0, 0, 10}));
    assertTrue(peer.closed());
    assertThrows(IllegalStateException.class, () -> peer.sendBinary(new byte[] {1}));
    assertThrows(IllegalStateException.class, () -> peer.sendText("{}"));
  }

  /** 回送 ACK 时发生 IOException：只丢弃该 ACK 不改变 closed，异常正文不得进入日志。 */
  @Test
  void acknowledgementSendIoFailureDropsAckWithoutClosingPeerAndDoesNotThrow() throws Exception {
    String secret = "synthetic-ack-write-secret";
    doThrow(new IOException(secret)).when(session).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    Logger logger = (Logger) LoggerFactory.getLogger(WebSocketIdeStreamPeer.class);
    Level previousLevel = logger.getLevel();
    boolean previousAdditivity = logger.isAdditive();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(logger.getLoggerContext());
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    try {
      assertDoesNotThrow(() -> peer.sendAcknowledgement(new byte[] {3, 0, 0, 0, 10}));
      assertFalse(peer.closed());
      assertTrue(
          peer.offer(new IdeStreamPeer.Inbound.Text("{}")), "ACK drop must not close receiver");
      assertEquals(1, logs.list.size(), "ACK write failure must be logged once");
      ILoggingEvent event = logs.list.getFirst();
      assertEquals(Level.DEBUG, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("phase=IDE_ACK_WRITE"));
      assertTrue(event.getFormattedMessage().contains("exception=IOException"));
      assertFalse(event.getFormattedMessage().contains(secret));
      assertNull(event.getThrowableProxy(), "ACK failure log must not carry the throwable");
    } finally {
      logger.detachAppender(logs);
      logs.stop();
      logger.setLevel(previousLevel);
      logger.setAdditive(previousAdditivity);
    }
  }

  /** 会话仍存活时底层抛出的 IllegalStateException 是未知状态，ACK 发送也必须原样抛出而不是吞掉。 */
  @Test
  void acknowledgementIllegalStateOnLiveSessionPropagates() throws Exception {
    when(session.isOpen()).thenReturn(true);
    doThrow(new IllegalStateException("live session failure")).when(session).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    assertThrows(
        IllegalStateException.class, () -> peer.sendAcknowledgement(new byte[] {3, 0, 0, 0, 10}));
    assertFalse(peer.closed());
  }

  /** 会话确已关闭时 IllegalStateException 视为关闭：ACK 丢弃、异常正文不入日志且不提前封死入站队列。 */
  @Test
  void acknowledgementIllegalStateOnClosedSessionIsSwallowed() throws Exception {
    String secret = "synthetic-ack-closed-secret";
    when(session.isOpen()).thenReturn(false);
    doThrow(new IllegalStateException(secret)).when(session).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
    Logger logger = (Logger) LoggerFactory.getLogger(WebSocketIdeStreamPeer.class);
    Level previousLevel = logger.getLevel();
    boolean previousAdditivity = logger.isAdditive();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(logger.getLoggerContext());
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    try {
      assertDoesNotThrow(() -> peer.sendAcknowledgement(new byte[] {3, 0, 0, 0, 10}));
      assertFalse(peer.closed());
      assertTrue(
          peer.offer(new IdeStreamPeer.Inbound.Text("{}")), "ACK drop must not close receiver");
      assertEquals(1, logs.list.size(), "closed-session ACK drop must be logged once");
      ILoggingEvent event = logs.list.getFirst();
      assertEquals(Level.DEBUG, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("phase=IDE_ACK_CLOSED"));
      assertTrue(event.getFormattedMessage().contains("exception=IllegalStateException"));
      assertFalse(event.getFormattedMessage().contains(secret));
      assertNull(event.getThrowableProxy(), "closed-session ACK log must not carry the throwable");
    } finally {
      logger.detachAppender(logs);
      logs.stop();
      logger.setLevel(previousLevel);
      logger.setAdditive(previousAdditivity);
    }
  }

  /**
   * data/control 发送仍 fail-closed：非 ACK 的数据或控制帧在遭遇 IOException 时必须立即关闭连接并抛出 IllegalStateException。
   */
  @Test
  void dataAndControlSendFailClosedOnIoException() throws Exception {
    WebSocketSession faultSession = mock(WebSocketSession.class);
    doThrow(new IOException("write failed")).when(faultSession).sendMessage(any());
    WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(faultSession);

    assertThrows(IllegalStateException.class, () -> peer.sendBinary(new byte[] {1, 2, 3}));
    assertTrue(peer.closed(), "peer must be closed on data send IOException");
    assertFalse(
        peer.offer(new IdeStreamPeer.Inbound.Text("{}")),
        "offer must be rejected after data send failure");

    WebSocketSession controlFaultSession = mock(WebSocketSession.class);
    doThrow(new IOException("control write failed")).when(controlFaultSession).sendMessage(any());
    WebSocketIdeStreamPeer controlPeer = new WebSocketIdeStreamPeer(controlFaultSession);
    assertThrows(IllegalStateException.class, () -> controlPeer.sendText("{\"type\":\"ERROR\"}"));
    assertTrue(controlPeer.closed(), "peer must be closed on control send IOException");
  }

  /**
   * 真实反向端点与 relay 的闭合接收流程：ACK 写失败故障注入（mock session 写入抛 IOException，模拟对端关闭读取，非真实 Tomcat 网络故障）。
   *
   * <p>闭合流程： 1. BODY 帧经 handler.handleBinaryMessage 入队 -> peer.poll 消费 -> relay.receiveBinary； 2.
   * 数据面消费后通过 relay.acknowledge 触发 ACK 写出，此处 mock session 抛出 IOException； 3. 对端随后发送 HTTP_END 经
   * handler.handleBinaryMessage 入队 -> peer.poll 消费 -> relay.receiveBinary 解码； 4. 关键验证：在旧生产代码中，第 2 步
   * ACK 失败会将 peer 提前置为 closed，导致第 3 步 handler 接收 END 时 peer.offer(END) 被拒（handler 触发
   * TOO_BIG_TO_PROCESS 并 detach），数据面 poll 不到 END； 修复后，ACK 失败仅丢弃该 ACK，后续 END 仍能正常入队被 poll 并被 relay
   * 解码为 HttpEnd。
   */
  @Test
  void reverseStreamEndpointWithAckWriteFaultAcceptsSubsequentEndMessage() throws Exception {
    DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(environmentId, "daemon-1");

    InMemoryIdeStreamTicketService ticketService = new InMemoryIdeStreamTicketService();
    IdeStreamTicketService.Issued issued = ticketService.issue(identity, IdeStreamMode.HTTP);
    String ticket = issued.ticket();

    IdeStreamPairingRegistry<IdeStreamPeer> pairings = new IdeStreamPairingRegistry<>();
    IdeStreamPairingRegistry<IdeStreamPeer>.Pairing pairing =
        pairings.open(ticket, identity, Duration.ofSeconds(5), () -> {});

    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    when(transport.connectionIdentity(environmentId)).thenReturn(Optional.of(identity));

    ClusterNodeRegistry clusterNodeRegistry = mock(ClusterNodeRegistry.class);
    when(clusterNodeRegistry.route(environmentId))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    IdeOwnerStreamForwarder ownerForwarder = mock(IdeOwnerStreamForwarder.class);

    IdeReverseStreamWebSocketHandler handler =
        new IdeReverseStreamWebSocketHandler(
            ticketService, pairings, transport, clusterNodeRegistry, ownerForwarder);

    // mock session 注入 ACK 写入 IOException（故障注入，非真实网络故障）
    WebSocketSession baseSession = mock(WebSocketSession.class);
    when(baseSession.getId()).thenReturn("ws-session-1");
    when(baseSession.getUri())
        .thenReturn(
            URI.create(
                "ws://localhost/environment-ide/stream/"
                    + ticket
                    + "?environment="
                    + environmentId));
    when(baseSession.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              WebSocketMessage<?> msg = invocation.getArgument(0);
              if (msg instanceof BinaryMessage bin && bin.getPayload().get(0) == 3) {
                throw new IOException("fault injection: simulated ACK write failure");
              }
              return null;
            })
        .when(baseSession)
        .sendMessage(any());

    // 1. 握手交付真实生产 peer
    handler.afterConnectionEstablished(baseSession);
    IdeStreamPeer peer = pairing.awaitPeer();
    assertNotNull(peer);

    IdeStreamRelay relay =
        new IdeStreamRelay(
            new IdeStreamRelay.Sink() {
              @Override
              public void sendText(String frame) {
                peer.sendText(frame);
              }

              @Override
              public void sendBinary(byte[] frame) {
                peer.sendBinary(frame);
              }

              @Override
              public void sendAcknowledgement(byte[] encodedAck) {
                peer.sendAcknowledgement(encodedAck);
              }
            });

    // 2. BODY 经由 handler 入队 -> peer.poll 消费 -> relay.receiveBinary
    byte[] bodyChunk = codec.encodeHttpBody(new byte[] {10, 20, 30});
    handler.handleBinaryMessage(baseSession, new BinaryMessage(ByteBuffer.wrap(bodyChunk)));
    IdeStreamPeer.Inbound bodyInbound = peer.poll(Duration.ofMillis(100));
    assertNotNull(bodyInbound, "body chunk must be polled");
    IdeStreamBinaryFrame body =
        relay.receiveBinary(((IdeStreamPeer.Inbound.Binary) bodyInbound).frame());

    // 3. 消费后触发 ACK 写出，此处命中故障注入抛出 IOException
    relay.acknowledge(body);

    // 4. 对端随后发送 HTTP_END 经由 handler 入队
    byte[] endBytes = codec.encodeHttpEnd();
    handler.handleBinaryMessage(baseSession, new BinaryMessage(ByteBuffer.wrap(endBytes)));

    // 5. 关键断言：在旧代码下 peer.offer(END) 被拒导致此处 poll 为 null，无法触达 END；
    //    修复后 END 正常被 poll 且 relay 成功解码为 HttpEnd
    IdeStreamPeer.Inbound endInbound = peer.poll(Duration.ofMillis(100));
    assertNotNull(endInbound, "HTTP_END must be received despite prior ACK write IOException");
    IdeStreamBinaryFrame decoded =
        relay.receiveBinary(((IdeStreamPeer.Inbound.Binary) endInbound).frame());
    assertInstanceOf(IdeStreamBinaryFrame.HttpEnd.class, decoded);

    // 6. 容器正常关闭连接后触发 afterConnectionClosed，peer 正式进入 closed 状态
    handler.afterConnectionClosed(baseSession, CloseStatus.NORMAL);
    assertTrue(peer.closed(), "peer must be closed after container connection close callback");
  }
}
