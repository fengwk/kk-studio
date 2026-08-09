package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** CompactionConfig 默认值与有效保留量计算。 */
class CompactionConfigTest {

  @Test
  void defaultsMatchUpstreamPi() {
    CompactionConfig config = CompactionConfig.DEFAULTS;
    assertTrue(config.enabled());
    assertEquals(16_384, config.reserveTokens());
    assertEquals(20_000, config.maxRecentTokens());
  }

  @Test
  void effectiveKeepRecentTokensIsHalfContextCappedByMaxRecent() {
    CompactionConfig config = new CompactionConfig(true, 16_384, 20_000);
    assertEquals(10_000, config.effectiveKeepRecentTokens(20_000));
    assertEquals(20_000, config.effectiveKeepRecentTokens(40_000));
    assertEquals(20_000, config.effectiveKeepRecentTokens(1_000_000));
    // 奇数窗口向下取整。
    assertEquals(10_000, config.effectiveKeepRecentTokens(20_001));
  }

  @Test
  void rejectsNonPositiveBudgets() {
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(true, 0, 20_000));
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(true, -1, 20_000));
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(true, 16_384, 0));
    // reserveTokens=1 会让 floor(0.8*1)=0 / floor(0.5*1)=0，两个输出预算都非正 -> 拒绝。
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(true, 1, 20_000));
    // reserveTokens=2 是最小合法值：两个预算都恰为正（1）。
    assertEquals(1L, new CompactionConfig(true, 2, 20_000).reserveTokens() / 2L);
  }

  @Test
  void rejectsNonPositiveContextWindow() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CompactionConfig.DEFAULTS.effectiveKeepRecentTokens(0));
  }
}
