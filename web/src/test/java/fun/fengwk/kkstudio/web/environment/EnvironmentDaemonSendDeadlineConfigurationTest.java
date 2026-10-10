package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonEndpoint;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Spring 实际装配与销毁共享 deadline timer 的契约。 */
class EnvironmentDaemonSendDeadlineConfigurationTest {

  /** 其它同类型 bean 已存在时仍解析指定 timer；两个真实 handler 连接复用它，context 关闭时清理延时任务。 */
  @Test
  void qualifiesAndOwnsSharedDeadlineTimer() {
    WebApplicationContextRunner runner =
        new WebApplicationContextRunner()
            .withUserConfiguration(
                EnvironmentDaemonWebSocketConfiguration.class,
                EnvironmentDaemonWebSocketHandler.class,
                TestBeans.class);
    ScheduledThreadPoolExecutor[] owned = new ScheduledThreadPoolExecutor[1];
    runner.run(
        context -> {
          assertTrue(
              context.getStartupFailure() == null, "Spring WebSocket configuration must start");
          ScheduledThreadPoolExecutor timer =
              context.getBean(
                  "environmentDaemonSendDeadlineTimer", ScheduledThreadPoolExecutor.class);
          owned[0] = timer;
          assertTrue(timer.getRemoveOnCancelPolicy());
          assertFalse(timer.getExecuteExistingDelayedTasksAfterShutdownPolicy());
          ScheduledThreadPoolExecutor other =
              context.getBean("unrelatedTimer", ScheduledThreadPoolExecutor.class);
          DaemonEndpoint endpoint = context.getBean(DaemonEndpoint.class);
          EnvironmentDaemonWebSocketHandler handler =
              context.getBean(EnvironmentDaemonWebSocketHandler.class);
          CountDownLatch release = new CountDownLatch(1);
          try {
            for (int i = 0; i < 2; i++) {
              WebSocketSession session = mock(WebSocketSession.class);
              when(session.getId()).thenReturn("connection-" + i);
              when(session.getExtensions())
                  .thenReturn(List.of(new WebSocketExtension("permessage-deflate")));
              when(session.isOpen()).thenReturn(true);
              // 两个在途帧的 send 阻塞，确认 deadline 同时注册在 Spring 托管的同一个 timer。
              doAnswer(
                      invocation -> {
                        release.await();
                        return null;
                      })
                  .when(session)
                  .sendMessage(any());
              handler.afterConnectionEstablished(session);
            }
            ArgumentCaptor<DaemonChannel> channels = ArgumentCaptor.forClass(DaemonChannel.class);
            verify(endpoint, times(2)).open(channels.capture());
            channels.getAllValues().forEach(channel -> channel.offerText("frame"));
            awaitQueueAtLeast(timer, 2);
            assertEquals(0, other.getQueue().size());
            assertSame(timer, context.getBean("environmentDaemonSendDeadlineTimer"));
            channels.getAllValues().forEach(DaemonChannel::close);
            awaitQueueSize(timer, 0);
          } finally {
            release.countDown();
          }
          // 在 Spring 生命周期终止时留一个延时任务，验证它不在 shutdown 后继续排队执行。
          timer.schedule(() -> {}, 1, TimeUnit.HOURS);
        });
    assertTrue(owned[0].isShutdown());
    assertEquals(0, owned[0].getQueue().size());
  }

  private static void awaitQueueSize(ScheduledThreadPoolExecutor timer, int expected) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (timer.getQueue().size() != expected && System.nanoTime() < until) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    assertEquals(expected, timer.getQueue().size());
  }

  /** 每连接除发送期限外还有一个周期 expire 任务，因此这里只断言共享 timer 至少承载了这些任务。 */
  private static void awaitQueueAtLeast(ScheduledThreadPoolExecutor timer, int atLeast) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (timer.getQueue().size() < atLeast && System.nanoTime() < until) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    assertTrue(
        timer.getQueue().size() >= atLeast,
        () -> "expected at least " + atLeast + " shared timer tasks");
  }

  @Configuration(proxyBeanMethods = false)
  static class TestBeans {
    @Bean
    DaemonEndpoint endpoint() {
      return mock(DaemonEndpoint.class);
    }

    @Bean(name = "nodeInstanceId")
    UUID nodeInstanceId() {
      return UUID.randomUUID();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledThreadPoolExecutor unrelatedTimer() {
      return new ScheduledThreadPoolExecutor(1);
    }
  }
}
