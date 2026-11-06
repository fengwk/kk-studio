package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;

/** 冻结约束的合并语义：沿执行树只收紧、不放宽，非法输出上限创建即拒绝。 */
class ThreadConstraintsTest {

  @Test
  void mergeTakesTheTighterValueForEveryDimension() {
    // 输出上限取 min，自动压缩取 AND：父与子都只能把约束收紧。
    ThreadConstraints parent = new ThreadConstraints(1_000, false);
    ThreadConstraints child = new ThreadConstraints(2_000, true);

    ThreadConstraints merged = child.merge(parent);

    assertEquals(1_000, merged.maxOutputTokens());
    assertEquals(false, merged.allowAutoCompaction());
  }

  @Test
  void mergeKeepsAnUnsetOutputLimitFromEitherSide() {
    assertNull(
        new ThreadConstraints(null, true)
            .merge(new ThreadConstraints(null, true))
            .maxOutputTokens());
    assertEquals(
        256,
        new ThreadConstraints(256, true)
            .merge(new ThreadConstraints(null, true))
            .maxOutputTokens());
    assertEquals(
        256,
        new ThreadConstraints(null, true)
            .merge(new ThreadConstraints(256, true))
            .maxOutputTokens());
  }

  @Test
  void nonPositiveOutputLimitIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadConstraints(0, true));
    assertThrows(IllegalArgumentException.class, () -> new ThreadConstraints(-1, true));
  }
}
