package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

class ThreadRuntimeStatusTest {

  @Test
  void mapsEveryValidContextToTheStablePublicStatus() {
    // 覆盖所有合法 context 形状，锁定对外 status vocabulary。
    assertEquals(
        ThreadRuntimeStatus.IDLE, ThreadRuntimeStatus.from(new ThreadContext.IdleOrHistorical()));
    assertEquals(ThreadRuntimeStatus.CONTINUATION_DUE, ThreadRuntimeStatus.from(continuation()));
    assertEquals(
        ThreadRuntimeStatus.MODEL_READY,
        ThreadRuntimeStatus.from(modelActive(ModelInvocationStatus.READY)));
    assertEquals(
        ThreadRuntimeStatus.MODEL_DISPATCHING,
        ThreadRuntimeStatus.from(modelActive(ModelInvocationStatus.DISPATCHING)));
    assertEquals(
        ThreadRuntimeStatus.MODEL_RUNNING,
        ThreadRuntimeStatus.from(modelActive(ModelInvocationStatus.RUNNING)));
    assertEquals(
        ThreadRuntimeStatus.APPLYING,
        ThreadRuntimeStatus.from(new ThreadContext.ModelTerminalPending(model())));
    assertEquals(
        ThreadRuntimeStatus.APPLYING,
        ThreadRuntimeStatus.from(
            new ThreadContext.ToolTerminalPending(model(), assistant(), List.of(), List.of())));
    assertEquals(
        ThreadRuntimeStatus.TOOL_WAITING_APPROVAL,
        ThreadRuntimeStatus.from(toolActive(ToolInvocationStatus.WAITING_APPROVAL)));
    assertEquals(
        ThreadRuntimeStatus.TOOL_RUNNING,
        ThreadRuntimeStatus.from(toolActive(ToolInvocationStatus.RUNNING)));
    assertEquals(
        ThreadRuntimeStatus.TOOL_DISPATCHING,
        ThreadRuntimeStatus.from(toolActive(ToolInvocationStatus.DISPATCHING)));
    assertEquals(
        ThreadRuntimeStatus.TOOL_READY,
        ThreadRuntimeStatus.from(toolActive(ToolInvocationStatus.READY)));
  }

  @Test
  void toolStatusUsesTheDocumentedBlockingPriority() {
    // sibling 顺序不能改变 WAITING_APPROVAL > RUNNING > DISPATCHING > READY 的优先级。
    assertEquals(
        ThreadRuntimeStatus.TOOL_WAITING_APPROVAL,
        ThreadRuntimeStatus.from(
            toolActive(
                ToolInvocationStatus.READY,
                ToolInvocationStatus.DISPATCHING,
                ToolInvocationStatus.RUNNING,
                ToolInvocationStatus.WAITING_APPROVAL)));
    assertEquals(
        ThreadRuntimeStatus.TOOL_RUNNING,
        ThreadRuntimeStatus.from(
            toolActive(
                ToolInvocationStatus.READY,
                ToolInvocationStatus.DISPATCHING,
                ToolInvocationStatus.RUNNING)));
    assertEquals(
        ThreadRuntimeStatus.TOOL_DISPATCHING,
        ThreadRuntimeStatus.from(
            toolActive(ToolInvocationStatus.READY, ToolInvocationStatus.DISPATCHING)));
  }

  @Test
  void rejectsInvalidActiveContextsInsteadOfInventingAStatus() {
    // terminal Model 与空/全 terminal Tool 列表必须由 classifier 产出 terminal-pending context。
    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.SUCCEEDED,
            ModelInvocationStatus.FAILED,
            ModelInvocationStatus.CANCELLED,
            ModelInvocationStatus.UNKNOWN)) {
      assertThrows(
          IllegalStateException.class, () -> ThreadRuntimeStatus.from(modelActive(status)));
    }
    assertThrows(IllegalStateException.class, () -> ThreadRuntimeStatus.from(toolActive()));
    assertThrows(
        IllegalStateException.class,
        () ->
            ThreadRuntimeStatus.from(
                toolActive(
                    ToolInvocationStatus.SUCCEEDED,
                    ToolInvocationStatus.FAILED,
                    ToolInvocationStatus.CANCELLED,
                    ToolInvocationStatus.UNKNOWN)));
    assertThrows(NullPointerException.class, () -> ThreadRuntimeStatus.from(null));
  }

  @Test
  void onlyIdleIsNotProcessing() {
    // processing 是 status 的稳定派生，不再由调用方比较 magic string。
    for (ThreadRuntimeStatus status : ThreadRuntimeStatus.values()) {
      if (status == ThreadRuntimeStatus.IDLE) {
        assertFalse(status.isProcessing());
      } else {
        assertTrue(status.isProcessing(), status.name());
      }
    }
  }

  private static ThreadContext.ContinuationDue continuation() {
    Entry entry = assistant();
    when(entry.payload())
        .thenReturn(
            new TurnEndPayload(new UUID(0L, 1L), TurnEndOutcome.COMPLETED, true, null, null));
    return new ThreadContext.ContinuationDue(entry);
  }

  private static ThreadContext.ModelActive modelActive(ModelInvocationStatus status) {
    ModelInvocation model = model();
    when(model.status()).thenReturn(status);
    return new ThreadContext.ModelActive(model);
  }

  private static ThreadContext.ToolActive toolActive(ToolInvocationStatus... statuses) {
    List<ToolInvocation> siblings =
        Arrays.stream(statuses)
            .map(
                status -> {
                  ToolInvocation invocation = mock(ToolInvocation.class);
                  when(invocation.status()).thenReturn(status);
                  return invocation;
                })
            .toList();
    return new ThreadContext.ToolActive(model(), assistant(), List.of(), siblings);
  }

  private static ModelInvocation model() {
    return mock(ModelInvocation.class);
  }

  private static Entry assistant() {
    return mock(Entry.class);
  }
}
