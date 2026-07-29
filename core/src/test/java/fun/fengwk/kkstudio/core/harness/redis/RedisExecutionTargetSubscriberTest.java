package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.nio.charset.StandardCharsets;

/** Verifies Redis listener failures remain isolated from the Redis listener thread. */
class RedisExecutionTargetSubscriberTest {

  @Test
  void decodesValidJsonAndDispatchesTarget() {
    RedisExecutionTargetDispatcher dispatcher = mock(RedisExecutionTargetDispatcher.class);
    RedisExecutionTargetSubscriber subscriber =
        new RedisExecutionTargetSubscriber(new ExecutionTargetJsonCodec(), dispatcher);
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 41L);

    subscriber.onMessage(
        message("{\"targetKind\":\"MODEL_INVOCATION\",\"targetId\":\"41\"}"), null);

    verify(dispatcher).dispatch(target);
  }

  @Test
  void isolatesInvalidJson() {
    RedisExecutionTargetDispatcher dispatcher = mock(RedisExecutionTargetDispatcher.class);
    RedisExecutionTargetSubscriber subscriber =
        new RedisExecutionTargetSubscriber(new ExecutionTargetJsonCodec(), dispatcher);

    assertDoesNotThrow(() -> subscriber.onMessage(message("not json"), null));

    verify(dispatcher, never()).dispatch(any());
  }

  @Test
  void isolatesDispatcherFailure() {
    RedisExecutionTargetDispatcher dispatcher = mock(RedisExecutionTargetDispatcher.class);
    doThrow(new IllegalStateException("executor rejected"))
        .when(dispatcher)
        .dispatch(new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 42L));
    RedisExecutionTargetSubscriber subscriber =
        new RedisExecutionTargetSubscriber(new ExecutionTargetJsonCodec(), dispatcher);

    assertDoesNotThrow(
        () ->
            subscriber.onMessage(
                message("{\"targetKind\":\"TOOL_INVOCATION\",\"targetId\":\"42\"}"), null));
  }

  private static Message message(String body) {
    Message message = mock(Message.class);
    when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
