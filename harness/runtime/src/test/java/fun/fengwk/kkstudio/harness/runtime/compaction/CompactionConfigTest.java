package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** CompactionConfig 统一 keep/reserve/threshold/manual/output 公式。 */
class CompactionConfigTest {

  @Test
  void defaultsExposeOnlyKeepAndOptionalFallback() {
    // 部署配置不再持久化 enabled/reserve/maxRecent 等重复策略字段。
    assertEquals(20_000, CompactionConfig.DEFAULT.keepRecentTokens());
    assertNull(CompactionConfig.DEFAULT.fallbackModel());
  }

  @Test
  void computesKeepReserveThresholdAndManualMinimum() {
    // 30k window：keep 与 manual 都受 C/2 限制；soft threshold 不低于 effectiveKeep。
    CompactionConfig config = new CompactionConfig(20_000, null);
    assertEquals(15_000L, config.effectiveKeep(30_000));
    assertEquals(15_000L, config.manualMinimum(30_000));
    assertEquals(16_384L, config.effectiveReserve(50_000));
    assertEquals(15_000L, config.softThreshold(30_000, 50_000));

    // 128k window：keep=20k，reserve=16,384，soft threshold=111,616，manual=40k。
    assertEquals(20_000L, config.effectiveKeep(128_000));
    assertEquals(40_000L, config.manualMinimum(128_000));
    assertEquals(111_616L, config.softThreshold(128_000, 32_000));
  }

  @Test
  void outputBudgetUsesPhaseRatioAndRemovedPrefixCap() {
    CompactionConfig config = new CompactionConfig(20_000, null);

    assertEquals(13_107L, config.outputBudget(CompactionPhase.FULL, 20_000, 50_000));
    assertEquals(8_192L, config.outputBudget(CompactionPhase.TURN_PREFIX, 20_000, 50_000));
    assertEquals(500L, config.outputBudget(CompactionPhase.HISTORY, 20_000, 500));
  }

  @Test
  void rejectsNonPositiveInputs() {
    CompactionConfig config = new CompactionConfig(20_000, null);
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(0, null));
    assertThrows(IllegalArgumentException.class, () -> config.effectiveKeep(0));
    assertThrows(IllegalArgumentException.class, () -> config.effectiveReserve(0));
    assertThrows(IllegalArgumentException.class, () -> config.softThreshold(1, 0));
    assertThrows(IllegalArgumentException.class, () -> config.manualMinimum(0));
    assertThrows(
        IllegalArgumentException.class, () -> config.outputBudget(CompactionPhase.FULL, 1, -1));
  }
}
