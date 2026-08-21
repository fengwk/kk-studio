package fun.fengwk.kkstudio.harness.runtime.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** SubagentConfig 的进程级并发/预算参数校验；观察参数不再包含轮询间隔。 */
class SubagentConfigTest {

  private static final Duration ONE_MS = Duration.ofMillis(1);
  private static final Duration HALF_MS = Duration.ofNanos(500_000);
  private static final Duration ONE_AND_HALF_MS = Duration.ofNanos(1_500_000);

  /** 合法配置的所有字段原样保留，maxTotalConcurrency 与 idleTimeout 支持 0 语义。 */
  @Test
  void preservesValidValues() {
    SubagentConfig config = new SubagentConfig(3, 2, 0, Duration.ZERO, 50);

    assertEquals(3, config.maxDepth());
    assertEquals(2, config.maxConcurrency());
    assertEquals(0, config.maxTotalConcurrency());
    assertEquals(Duration.ZERO, config.idleTimeout());
    assertEquals(50, config.maxTurns());
  }

  /** maxTotalConcurrency 非负；0 表示不限总量。 */
  @Test
  void rejectsNegativeMaxTotalConcurrency() {
    SubagentConfig zero = new SubagentConfig(1, 1, 0, ONE_MS, 1);
    assertEquals(0, zero.maxTotalConcurrency());
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, -1, ONE_MS, 1));
  }

  /** maxDepth/maxConcurrency/maxTurns 任一非正都会整体拒绝。 */
  @Test
  void rejectsNonPositiveCoreBudgets() {
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(0, 1, 0, ONE_MS, 1));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 0, 0, ONE_MS, 1));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, ONE_MS, 0));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(-1, 1, 0, ONE_MS, 1));
  }

  /** idleTimeout 可空拒绝、不可为负、零表示关闭；非零必须是整毫秒。 */
  @Test
  void rejectsInvalidIdleTimeout() {
    assertThrows(NullPointerException.class, () -> new SubagentConfig(1, 1, 0, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubagentConfig(1, 1, 0, Duration.ofMillis(-1), 1));
    assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, HALF_MS, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, ONE_AND_HALF_MS, 1));
  }

  /** 错误消息给出字段归属，便于运维定位非法配置。 */
  @Test
  void exposesCanonicalErrorMessages() {
    IllegalArgumentException idle =
        assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, HALF_MS, 1));
    assertTrue(idle.getMessage().contains("idleTimeout"), idle.getMessage());

    IllegalArgumentException budget =
        assertThrows(IllegalArgumentException.class, () -> new SubagentConfig(0, 1, 0, ONE_MS, 1));
    assertTrue(budget.getMessage().contains("maxDepth"), budget.getMessage());
  }
}
