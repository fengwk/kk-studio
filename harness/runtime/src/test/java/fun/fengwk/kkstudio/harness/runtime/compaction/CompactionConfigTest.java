package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;

/** CompactionConfig 统一 keep/reserve/output 公式。 */
class CompactionConfigTest {

  @Test
  void defaultsExposeOnlyKeep() {
    // 部署配置仅暴露 keepRecentTokens。
    assertEquals(20_000, CompactionConfig.DEFAULT.keepRecentTokens());
  }

  @Test
  void computesKeepAndReserveFromWindowAndOutput() {
    CompactionConfig config = new CompactionConfig(20_000);

    // 30k window：keep 与 reserve 都受各自上限限制。
    assertEquals(15_000L, config.effectiveKeep(30_000));
    assertEquals(16_384L, config.effectiveReserve(50_000));

    // 128k window：keep=20k，reserve=16,384。
    assertEquals(20_000L, config.effectiveKeep(128_000));
    assertEquals(16_384L, config.effectiveReserve(32_000));
    assertEquals(1_000L, config.effectiveReserve(1_000));
  }

  @Test
  void outputBudgetIsEightyPercentOfTheFixedReserve() {
    CompactionConfig config = new CompactionConfig(20_000);

    assertEquals(13_107L, config.outputBudget(20_000));
    assertEquals(4_000L, config.outputBudget(5_000));
  }

  @Test
  void rejectsNonPositiveInputs() {
    CompactionConfig config = new CompactionConfig(20_000);
    assertThrows(IllegalArgumentException.class, () -> new CompactionConfig(0));
    assertThrows(IllegalArgumentException.class, () -> config.effectiveKeep(0));
    assertThrows(IllegalArgumentException.class, () -> config.effectiveReserve(0));
    assertThrows(IllegalArgumentException.class, () -> config.outputBudget(0));
  }
}
