package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 冻结约束的合并语义：沿执行树只收紧、不放宽，非法输出上限创建即拒绝。 */
class ThreadConstraintsTest {

  @Test
  void mergeTakesTheTighterValueForEveryDimension() {
    // 输出上限取 min，cache 禁用取 OR，自动压缩取 AND：父与子都只能把约束收紧。
    ThreadConstraints parent = new ThreadConstraints(1_000, false, false);
    ThreadConstraints child = new ThreadConstraints(2_000, true, true);

    ThreadConstraints merged = child.merge(parent);

    assertEquals(1_000, merged.maxOutputTokens());
    assertEquals(true, merged.promptCacheDisabled());
    assertEquals(false, merged.allowAutoCompaction());
  }

  @Test
  void mergeKeepsAnUnsetOutputLimitFromEitherSide() {
    assertNull(
        new ThreadConstraints(null, false, true)
            .merge(new ThreadConstraints(null, true, true))
            .maxOutputTokens());
    assertEquals(
        256,
        new ThreadConstraints(256, false, true)
            .merge(new ThreadConstraints(null, false, true))
            .maxOutputTokens());
    assertEquals(
        256,
        new ThreadConstraints(null, false, true)
            .merge(new ThreadConstraints(256, false, true))
            .maxOutputTokens());
  }

  @Test
  void nonPositiveOutputLimitIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadConstraints(0, false, true));
    assertThrows(IllegalArgumentException.class, () -> new ThreadConstraints(-1, false, true));
  }
}
