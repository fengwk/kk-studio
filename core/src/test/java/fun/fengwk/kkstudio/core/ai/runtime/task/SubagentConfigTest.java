package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** SubagentConfig 的进程级并发/预算/观察参数校验。 */
class SubagentConfigTest {

  private static final Duration ONE_MS = Duration.ofMillis(1);
  private static final Duration HALF_MS = Duration.ofNanos(500_000);
  private static final Duration ONE_AND_HALF_MS = Duration.ofNanos(1_500_000);

  /** 合法配置的所有字段原样保留，maxTotalConcurrency 与 idleTimeout 允许空/零语义。 */
  @Test
  void preservesValidValues() {
    SubagentConfig config =
        new SubagentConfig(3, 2, null, Duration.ZERO, 50, Duration.ofMillis(100));

    assertEquals(3, config.maxDepth());
    assertEquals(2, config.maxConcurrency());
    assertNull(config.maxTotalConcurrency());
    assertEquals(Duration.ZERO, config.idleTimeout());
    assertEquals(50, config.maxTurns());
    assertEquals(Duration.ofMillis(100), config.pollInterval());
  }

  /** maxTotalConcurrency 非空时必须为正；null 表示不限总量。 */
  @Test
  void rejectsNonPositiveMaxTotalConcurrency() {
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, 0, ONE_MS, 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, -1, ONE_MS, 1, ONE_MS));
  }

  /** maxDepth/maxConcurrency/maxTurns 任一非正都会整体拒绝。 */
  @Test
  void rejectsNonPositiveCoreBudgets() {
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(0, 1, null, ONE_MS, 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 0, null, ONE_MS, 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, null, ONE_MS, 0, ONE_MS));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(-1, 1, null, ONE_MS, 1, ONE_MS));
  }

  /** idleTimeout 可空拒绝、不可为负、零表示关闭；非零必须是整毫秒。 */
  @Test
  void rejectsInvalidIdleTimeout() {
    assertThrows(NullPointerException.class, () -> new SubagentConfig(1, 1, null, null, 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubagentConfig(1, 1, null, Duration.ofMillis(-1), 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, null, HALF_MS, 1, ONE_MS));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubagentConfig(1, 1, null, ONE_AND_HALF_MS, 1, ONE_MS));
  }

  /** pollInterval 必须是非零的正整毫秒；亚毫秒精度被拒绝。 */
  @Test
  void rejectsInvalidPollInterval() {
    assertThrows(NullPointerException.class, () -> new SubagentConfig(1, 1, null, ONE_MS, 1, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubagentConfig(1, 1, null, ONE_MS, 1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new SubagentConfig(1, 1, null, ONE_MS, 1, HALF_MS));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubagentConfig(1, 1, null, ONE_MS, 1, ONE_AND_HALF_MS));
  }

  /** 错误消息给出字段归属，便于运维定位非法配置。 */
  @Test
  void exposesCanonicalErrorMessages() {
    IllegalArgumentException idle =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SubagentConfig(1, 1, null, HALF_MS, 1, ONE_MS));
    assertTrue(idle.getMessage().contains("idleTimeout"), idle.getMessage());

    IllegalArgumentException poll =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SubagentConfig(1, 1, null, ONE_MS, 1, HALF_MS));
    assertTrue(poll.getMessage().contains("pollInterval"), poll.getMessage());

    IllegalArgumentException budget =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SubagentConfig(0, 1, null, ONE_MS, 1, ONE_MS));
    assertTrue(budget.getMessage().contains("maxDepth"), budget.getMessage());
  }
}
