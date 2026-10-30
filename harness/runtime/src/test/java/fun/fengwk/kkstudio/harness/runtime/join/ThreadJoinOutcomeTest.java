package fun.fengwk.kkstudio.harness.runtime.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** {@link ThreadJoinOutcome} 终态枚举与 wireName 契约测试。 */
class ThreadJoinOutcomeTest {

  @Test
  void outcomeValuesMatchWireNames() {
    // 测试意图：验证 ThreadJoinOutcome 枚举值及其与 wire 协议一致的 wireName。
    assertEquals("completed", ThreadJoinOutcome.COMPLETED.wireName());
    assertEquals("error", ThreadJoinOutcome.ERROR.wireName());
    assertEquals("cancelled", ThreadJoinOutcome.CANCELLED.wireName());
  }

  @Test
  void standardEnumMethodsFunctionCorrectly() {
    // 测试意图：验证标准枚举 values 与 valueOf 行为。
    assertEquals(3, ThreadJoinOutcome.values().length);
    assertEquals(ThreadJoinOutcome.COMPLETED, ThreadJoinOutcome.valueOf("COMPLETED"));
    assertEquals(ThreadJoinOutcome.ERROR, ThreadJoinOutcome.valueOf("ERROR"));
    assertEquals(ThreadJoinOutcome.CANCELLED, ThreadJoinOutcome.valueOf("CANCELLED"));
    assertNotNull(ThreadJoinOutcome.COMPLETED.toString());
  }
}
