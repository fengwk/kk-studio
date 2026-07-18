package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** SSE 关闭时取消轮询 future，避免永久 no-op 任务。 */
class StudioHarnessThreadSseEmitterTest {

  @Test
  void completeCancelsFurtherPolling() throws Exception {
    HarnessObservabilityQueryService query = mock(HarnessObservabilityQueryService.class);
    AtomicInteger calls = new AtomicInteger();
    when(query.listThreadEvents(anyString(), anyLong(), anyInt()))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              ThreadEventDTO event = new ThreadEventDTO();
              event.setEventId("9");
              event.setThreadId("1");
              event.setEventType("thread_idle");
              event.setPayloadJson("{}");
              return List.of(event);
            });
    SseEmitter emitter = StudioHarnessThreadSseEmitter.stream("1", 0L, query);
    assertNotNull(emitter);
    Thread.sleep(250);
    emitter.complete();
    int afterClose = calls.get();
    Thread.sleep(500);
    // 关闭后不应继续显著增长（允许关闭瞬间一次竞态）。
    assertTrue(calls.get() <= afterClose + 1);
    verify(query, atLeastOnce()).listThreadEvents(anyString(), anyLong(), anyInt());
  }
}
