package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 端到端：notifier publish -> Redis Pub/Sub -> RedisMessageListenerContainer 收到；消息是 deterministic
 * {@code {targetKind, targetId}} JSON。
 */
class RedisActivationNotifierIntegrationTest extends RedisSpringTestSupport {

  private static final String CHANNEL = "kk-studio:harness:signal";

  @Autowired private StringRedisTemplate stringRedisTemplate;
  @Autowired private ExecutionTargetJsonCodec targetCodec;
  @Autowired private ActivationNotifier notifier;

  private RedisMessageListenerContainer container;
  private LinkedBlockingQueue<String> received;
  private MessageListener listener;

  @BeforeEach
  void subscribe() throws InterruptedException {
    received = new LinkedBlockingQueue<>();
    listener =
        (message, pattern) -> received.add(new String(message.getBody(), StandardCharsets.UTF_8));

    RedisConnectionFactory connectionFactory = stringRedisTemplate.getConnectionFactory();
    assertNotNull(connectionFactory);
    container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(listener, new ChannelTopic(CHANNEL));
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
  void publishProducesDeterministicJson() throws InterruptedException {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 12345L);
    notifier.notifyAfterCommit(target);

    String body = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(body, "subscriber did not receive message within 5s");
    assertEquals(targetCodec.encode(target), body);
    assertEquals(target, targetCodec.decode(body));
  }

  @Test
  void threadActivationTargetRoundTripsThroughChannel() throws InterruptedException {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 99L);
    notifier.notifyAfterCommit(target);

    String body = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(body);
    assertEquals(new ExecutionTarget(ExecutionTargetKind.THREAD, 99L), targetCodec.decode(body));
  }
}
