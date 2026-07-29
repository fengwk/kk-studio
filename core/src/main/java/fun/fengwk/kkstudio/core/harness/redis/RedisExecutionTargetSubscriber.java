package fun.fengwk.kkstudio.core.harness.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Decodes one Redis activation message and isolates malformed payloads and dispatch failures. */
@Slf4j
public final class RedisExecutionTargetSubscriber implements MessageListener {

  private final ExecutionTargetJsonCodec targetCodec;
  private final RedisExecutionTargetDispatcher dispatcher;

  public RedisExecutionTargetSubscriber(
      ExecutionTargetJsonCodec targetCodec, RedisExecutionTargetDispatcher dispatcher) {
    this.targetCodec = Objects.requireNonNull(targetCodec, "targetCodec");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      Message redisMessage = Objects.requireNonNull(message, "message");
      String body =
          new String(
              Objects.requireNonNull(redisMessage.getBody(), "message.body"),
              StandardCharsets.UTF_8);
      dispatcher.dispatch(targetCodec.decode(body));
    } catch (RuntimeException error) {
      log.warn("Redis activation message was ignored", error);
    }
  }
}
