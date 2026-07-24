package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.redis.RedisRealtimeEventTail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** SSE close cancels the Redis tail worker so block-read loops stop. */
class StudioHarnessThreadSseEmitterTest {

  @Test
  void completeStopsFurtherTailReads() throws Exception {
    RedisRealtimeEventTail tail = mock(RedisRealtimeEventTail.class);
    AtomicInteger calls = new AtomicInteger();
    when(tail.readAfter(anyLong(), anyString(), anyInt(), any(Duration.class)))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              Thread.sleep(50);
              return List.of(
                  new RedisRealtimeEventTail.Record(
                      "1-0",
                      "{\"threadId\":\"1\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"2\",\"attempt\":1,\"type\":\"MODEL_DELTA\",\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"x\"},\"createdAt\":\"2026-01-01T00:00:00Z\"}"));
            });
    SseEmitter emitter = StudioHarnessThreadSseEmitter.stream(1L, "0-0", tail);
    assertNotNull(emitter);
    Thread.sleep(250);
    emitter.complete();
    int afterClose = calls.get();
    Thread.sleep(500);
    assertTrue(calls.get() <= afterClose + 1);
    verify(tail, atLeastOnce()).readAfter(anyLong(), anyString(), anyInt(), any(Duration.class));
  }
}
