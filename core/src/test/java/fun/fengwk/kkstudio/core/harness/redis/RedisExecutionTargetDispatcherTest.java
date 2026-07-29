package fun.fengwk.kkstudio.core.harness.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies every durable activation target reaches only its prescribed local entry point. */
class RedisExecutionTargetDispatcherTest {

  @Test
  void dispatchesThreadTargetToThreadKick() {
    ThreadKick threadKick = mock(ThreadKick.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);

    dispatcher(threadKick, modelWorker, Runnable::run, toolWorker, Runnable::run)
        .dispatch(new ExecutionTarget(ExecutionTargetKind.THREAD, 11L));

    verify(threadKick).kick(11L);
    verify(modelWorker, never()).dispatch(11L);
    verify(toolWorker, never()).dispatch(11L);
  }

  @Test
  void submitsModelTargetToModelWorkerScheduler() {
    ThreadKick threadKick = mock(ThreadKick.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);
    AtomicReference<Runnable> submitted = new AtomicReference<>();

    dispatcher(threadKick, modelWorker, submitted::set, toolWorker, Runnable::run)
        .dispatch(new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 12L));

    verify(modelWorker, never()).dispatch(12L);
    verify(threadKick, never()).kick(12L);
    verify(toolWorker, never()).dispatch(12L);
    submitted.get().run();
    verify(modelWorker).dispatch(12L);
  }

  @Test
  void submitsToolTargetToToolWorkerScheduler() {
    ThreadKick threadKick = mock(ThreadKick.class);
    ModelWorker modelWorker = mock(ModelWorker.class);
    ToolWorker toolWorker = mock(ToolWorker.class);
    AtomicReference<Runnable> submitted = new AtomicReference<>();

    dispatcher(threadKick, modelWorker, Runnable::run, toolWorker, submitted::set)
        .dispatch(new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 13L));

    verify(toolWorker, never()).dispatch(13L);
    verify(threadKick, never()).kick(13L);
    verify(modelWorker, never()).dispatch(13L);
    submitted.get().run();
    verify(toolWorker).dispatch(13L);
  }

  private static RedisExecutionTargetDispatcher dispatcher(
      ThreadKick threadKick,
      ModelWorker modelWorker,
      Executor modelWorkerScheduler,
      ToolWorker toolWorker,
      Executor toolWorkerScheduler) {
    return new RedisExecutionTargetDispatcher(
        threadKick, modelWorker, modelWorkerScheduler, toolWorker, toolWorkerScheduler);
  }
}
