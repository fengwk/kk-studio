package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamPairingRegistry;
import fun.fengwk.kkstudio.harness.environment.server.IdeStreamTicketService;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 浏览器 WS 入站队列的帧预算语义：零载荷分段同样受帧数预算约束，预算满时阻塞生产者容器线程，断开时唤醒并以 fail-closed 结束。
 *
 * <p>只验证 {@link IdeBrowserStreamSession#offer} 的预算与唤醒行为：不启动转发线程（不调用 {@code start()}）、不建真实 WS 会话，
 * 依赖全部用 mock 或不参与转发的真实空实现，因此不会触碰网络、Daemon 或数据库。
 */
class IdeBrowserStreamSessionInboundBudgetTest {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("55555555-5555-5555-5555-555555555555");

  @Test
  void zeroLengthFramesConsumeFrameBudgetAndDetachWakesBlockedProducer() throws Exception {
    IdeBrowserStreamSession session = newSession();
    for (int index = 0; index < IdeBrowserStreamSession.MAX_INBOUND_FRAMES; index++) {
      assertTrue(
          session.offer(new IdeBrowserStreamSession.Frame(false, new byte[0], false)),
          "zero-length frame " + index + " must fit the frame budget");
    }

    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Boolean> blockedResult = new AtomicReference<>();
    Thread producer =
        Thread.ofVirtual()
            .name("inbound-budget-producer")
            .start(
                () -> {
                  started.countDown();
                  blockedResult.set(
                      session.offer(new IdeBrowserStreamSession.Frame(false, new byte[0], false)));
                });
    assertTrue(started.await(5, TimeUnit.SECONDS), "producer must start");

    // 预算已满：零载荷分段也不能继续入队（否则无字节的文本消息可无界增长），生产者必须阻塞而不是返回。
    Thread.sleep(200);
    assertNull(blockedResult.get(), "producer must block while the frame budget is full");
    assertTrue(producer.isAlive(), "producer must still be blocked while the frame budget is full");

    session.onBrowserDetached();

    producer.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(producer.isAlive(), "detach must wake the blocked producer");
    assertEquals(Boolean.FALSE, blockedResult.get(), "woken producer must fail-closed");
    assertFalse(
        session.offer(new IdeBrowserStreamSession.Frame(false, new byte[0], true)),
        "closed session must reject further frames");
  }

  private static IdeBrowserStreamSession newSession() {
    IdeBrowserSessionRegistry.Session generation =
        new IdeBrowserSessionRegistry.Session(
            new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID()),
            Set.of("/home/dev/project"));
    IdeStreamDeps deps =
        new IdeStreamDeps(
            mock(IdeCommandTransport.class),
            mock(IdeStreamTicketService.class),
            new IdeStreamPairingRegistry<>(),
            mock(IdeGatewayProperties.class));
    return new IdeBrowserStreamSession(
        mock(WebSocketSession.class),
        ENVIRONMENT,
        generation,
        "/ws/echo",
        List.of(),
        deps,
        () -> {});
  }
}
