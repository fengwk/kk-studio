package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SubagentConfig 的进程级并发与预算参数校验。 */
class SubagentConfigTest {

  /** 合法配置的所有字段原样保留，maxTotalConcurrency 支持 0（不设树级上限）语义。 */
  @Test
  void preservesValidValues() {
    SubagentConfig config = new SubagentConfig(3, 2, 0, 50);

    assertEquals(3, config.maxDepth());
    assertEquals(2, config.maxConcurrency());
    assertEquals(0, config.maxTotalConcurrency());
    assertEquals(50, config.maxTurns());
  }

  /** maxTotalConcurrency 非负；0 表示不限总量。 */
  @Test
  void rejectsNegativeMaxTotalConcurrency() {
    SubagentConfig zero = new SubagentConfig(1, 1, 0, 1);
    assertEquals(0, zero.maxTotalConcurrency());
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, -1, 1));
  }

  /** maxDepth/maxConcurrency/maxTurns 任一非正都会整体拒绝。 */
  @Test
  void rejectsNonPositiveCoreBudgets() {
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(0, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(-1, 1, 0, 1));
  }

  /** 错误消息给出字段归属，便于运维定位非法配置。 */
  @Test
  void exposesCanonicalErrorMessages() {
    IllegalArgumentException budget =
        assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(0, 1, 0, 1));
    assertTrue(budget.getMessage().contains("maxDepth"), budget.getMessage());
  }
}
