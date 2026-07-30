package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.time.Instant;

/** Verifies PostgreSQL durable target rows reach exactly one Runtime entry point. */
class HarnessExecutionTargetHandlerTest {

  @Test
  void activatesThreadThroughReconciler() {
    ThreadReconciler threadReconciler = mock(ThreadReconciler.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);

    boolean handled =
        handler(threadReconciler, modelWorker, toolWorker)
            .handle(row(ExecutionTargetKind.THREAD, 11L));

    assertTrue(handled);
    verify(threadReconciler).activate(11L);
    verify(modelWorker, never()).dispatch(11L);
    verify(toolWorker, never()).dispatch(11L);
  }

  @Test
  void claimsModelTargetSynchronously() {
    ThreadReconciler threadReconciler = mock(ThreadReconciler.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);
    when(modelWorker.activate(12L)).thenReturn(true);

    boolean handled =
        handler(threadReconciler, modelWorker, toolWorker)
            .handle(row(ExecutionTargetKind.MODEL_INVOCATION, 12L));

    assertTrue(handled);
    verify(modelWorker).activate(12L);
    verify(threadReconciler, never()).activate(12L);
    verify(toolWorker, never()).dispatch(12L);
  }

  @Test
  void delegatesToolClaimSynchronouslyAndPreservesItsResult() {
    ThreadReconciler threadReconciler = mock(ThreadReconciler.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);
    when(toolWorker.dispatch(13L)).thenReturn(false);

    boolean handled =
        handler(threadReconciler, modelWorker, toolWorker)
            .handle(row(ExecutionTargetKind.TOOL_INVOCATION, 13L));

    assertFalse(handled);
    verify(toolWorker).dispatch(13L);
    verify(threadReconciler, never()).activate(13L);
    verify(modelWorker, never()).activate(13L);
  }

  private static HarnessExecutionTargetHandler handler(
      ThreadReconciler threadReconciler, ModelWorker modelWorker, ToolWorker toolWorker) {
    return new HarnessExecutionTargetHandler(threadReconciler, modelWorker, toolWorker);
  }

  private static ExecutionTargetRow row(ExecutionTargetKind kind, long targetId) {
    return new ExecutionTargetRow(
        kind, targetId, null, Instant.parse("2026-01-01T00:00:00Z"), true);
  }
}
