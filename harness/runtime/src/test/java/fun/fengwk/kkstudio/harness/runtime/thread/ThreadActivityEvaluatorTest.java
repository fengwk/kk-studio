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
import java.util.Set;
import java.util.UUID;

class ThreadActivityEvaluatorTest {

  @Test
  void statusValuesAreExactlyTheThreePublicStates() {
    // 测试意图：对外运行时状态只有 RUNNING / WAITING / IDLE 三态，与 executionControl 正交。
    assertEquals(
        Set.of(ThreadRuntimeStatus.RUNNING, ThreadRuntimeStatus.WAITING, ThreadRuntimeStatus.IDLE),
        Set.of(ThreadRuntimeStatus.values()));
  }

  @Test
  void mapsLocalContextsToRunningOrIdle() {
    // 测试意图：验证本地 context 投影：仅 IdleOrHistorical 映射为 IDLE，其余本地活跃阶段统一为 RUNNING。
    assertEquals(
        ThreadRuntimeStatus.IDLE,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            new ThreadContext.IdleOrHistorical(),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, continuation(), false, false, 0, false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            modelActive(ModelInvocationStatus.READY),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            modelActive(ModelInvocationStatus.RUNNING),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            new ThreadContext.ModelTerminalPending(model()),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            new ThreadContext.ToolTerminalPending(model(), assistant(), List.of(), List.of()),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            toolActive(ToolInvocationStatus.WAITING_APPROVAL),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            toolActive(ToolInvocationStatus.WAITING_INPUT),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            toolActive(ToolInvocationStatus.RUNNING),
            false,
            false,
            0,
            false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE,
            toolActive(ToolInvocationStatus.READY),
            false,
            false,
            0,
            false));
  }

  @Test
  void evaluatorEnforcesInvariantsAndEvaluatesStatesAccurately() {
    ThreadContext idle = new ThreadContext.IdleOrHistorical();
    ThreadContext active = continuation();

    // 1. 参数校验
    assertThrows(
        NullPointerException.class,
        () -> ThreadActivityEvaluator.evaluate(null, idle, false, false, 0, false));
    assertThrows(
        NullPointerException.class,
        () ->
            ThreadActivityEvaluator.evaluate(
                ThreadExecutionControl.RUNNABLE, null, false, false, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadActivityEvaluator.evaluate(
                ThreadExecutionControl.RUNNABLE, idle, false, false, -1, false));
    assertThrows(
        NullPointerException.class,
        () ->
            ThreadActivityEvaluator.isQuiescent(
                ThreadExecutionControl.RUNNABLE, null, false, false, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadActivityEvaluator.isQuiescent(
                ThreadExecutionControl.RUNNABLE, idle, false, false, -1, false));

    // 2. 本地在途执行与 open compaction：始终 RUNNING
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, active, false, false, 0, false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, active, false, false, 0, false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, true, false, 0, false));

    // 3. input demand 与 executionControl 正交性
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, true, 0, false));
    assertEquals(
        ThreadRuntimeStatus.IDLE,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, true, 0, false));
    assertEquals(
        ThreadRuntimeStatus.WAITING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, true, 1, false));

    // 4. WAITING 与 IDLE
    assertEquals(
        ThreadRuntimeStatus.WAITING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 2, false));
    assertEquals(
        ThreadRuntimeStatus.IDLE,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 0, false));

    // 5. isQuiescent 收敛判断
    assertTrue(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, active, false, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, true, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, true, 0, false));
    // STOPPED 保留通知但不自启：输入需求不阻挡收敛，与上方 RUNNABLE 输入需求仍阻挡形成对照。
    assertTrue(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.STOPPED, idle, false, true, 0, false));
    // 未确认执行无论 STOPPED 与否都保持 RUNNING，且不得判定为收敛。
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, false, 0, true));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.STOPPED, idle, false, false, 0, true));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 1, false));
  }

  private static List<ToolInvocation> toolInvocations(ToolInvocationStatus... statuses) {
    return Arrays.stream(statuses)
        .map(
            status -> {
              ToolInvocation invocation = mock(ToolInvocation.class);
              when(invocation.status()).thenReturn(status);
              return invocation;
            })
        .toList();
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
    List<ToolInvocation> siblings = toolInvocations(statuses);
    return new ThreadContext.ToolActive(model(), assistant(), List.of(), siblings);
  }

  private static ModelInvocation model() {
    return mock(ModelInvocation.class);
  }

  private static Entry assistant() {
    return mock(Entry.class);
  }
}
