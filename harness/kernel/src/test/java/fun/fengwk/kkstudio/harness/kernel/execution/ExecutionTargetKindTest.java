package fun.fengwk.kkstudio.harness.kernel.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** ExecutionTargetKind 枚举成员集合稳定性测试。 */
class ExecutionTargetKindTest {

  @Test
  void fixedEnumMembers() {
    assertEquals(3, ExecutionTargetKind.values().length);
    assertSame(ExecutionTargetKind.THREAD, ExecutionTargetKind.valueOf("THREAD"));
    assertSame(
        ExecutionTargetKind.MODEL_INVOCATION, ExecutionTargetKind.valueOf("MODEL_INVOCATION"));
    assertSame(ExecutionTargetKind.TOOL_INVOCATION, ExecutionTargetKind.valueOf("TOOL_INVOCATION"));
  }
}
