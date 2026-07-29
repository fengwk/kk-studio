package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 端到端：notifier publish -> Redis Pub/Sub -> production subscriber decode -> target dispatcher。 */
class RedisActivationNotifierIntegrationTest extends RedisSpringTestSupport {

  private static final String CHANNEL = "kk-studio:harness:signal";

  @Autowired private StringRedisTemplate stringRedisTemplate;
  @Autowired private ActivationNotifier notifier;

  private RedisMessageListenerContainer container;
  private LinkedBlockingQueue<ExecutionTarget> received;

  @BeforeEach
  void subscribe() throws InterruptedException {
    received = new LinkedBlockingQueue<>();
    RedisExecutionTargetDispatcher dispatcher =
        new RedisExecutionTargetDispatcher(
            threadId -> received.add(new ExecutionTarget(ExecutionTargetKind.THREAD, threadId)),
            mock(ModelWorker.class),
            Runnable::run,
            mock(ToolWorker.class),
            Runnable::run);

    RedisConnectionFactory connectionFactory = stringRedisTemplate.getConnectionFactory();
    assertNotNull(connectionFactory);
    container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(
        new RedisExecutionTargetSubscriber(new ExecutionTargetJsonCodec(), dispatcher),
        new ChannelTopic(CHANNEL));
    container.afterPropertiesSet();
    container.start();

    // Wait briefly for subscription registration.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!container.isListening() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
  }

  @AfterEach
  void unsubscribe() throws Exception {
    if (container != null) {
      container.stop();
      container.destroy();
    }
  }

  @Test
  void publishReachesProductionSubscriberAndDispatcher() throws InterruptedException {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 12345L);
    notifier.notifyAfterCommit(target);

    ExecutionTarget dispatched = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(dispatched, "production subscriber did not dispatch target within 5s");
    assertEquals(target, dispatched);
  }
}
