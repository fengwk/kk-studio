package fun.fengwk.kkstudio.platform.vfs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link VfsEditDiff}：只展示被替换位置及其上下文，超长审查被拒绝。 */
class VfsEditDiffTest {

  /** 唯一替换生成带上下文的 unified hunk，文件头使用用户可见的 vfs 路径。 */
  @Test
  void producesAUnifiedHunkAroundTheReplacement() {
    String diff =
        VfsEditDiff.unified(
            "vfs:/sessions/s/notes.txt", "alpha\nbeta\ngamma\ndelta\n", "gamma", "GAMMA", false);

    assertTrue(
        diff.startsWith("--- vfs:/sessions/s/notes.txt\n+++ vfs:/sessions/s/notes.txt\n"), diff);
    assertTrue(diff.contains("@@ -1,4 +1,4 @@"), diff);
    assertTrue(diff.contains(" alpha\n beta\n-gamma\n+GAMMA\n delta"), diff);
  }

  /** replace_all 的每处替换各成一个 hunk，行号按前面的增删累积偏移。 */
  @Test
  void emitsOneHunkPerReplacementWithCumulativeLineOffsets() {
    String diff =
        VfsEditDiff.unified(
            "vfs:/sessions/s/a.txt",
            "a\nb\nc\nx\nd\ne\nf\ng\nh\ni\nx\nk\nl\nm\nn\n",
            "x",
            "X",
            true);

    int first = diff.indexOf("@@ -1,7 +1,7 @@");
    int second = diff.indexOf("@@ -8,7 +8,7 @@");
    assertTrue(first >= 0, diff);
    assertTrue(second > first, diff);
    assertTrue(diff.chars().filter(c -> c == '+').count() > 2, diff);
  }

  /** 审查 diff 超过体积上限时拒绝，要求拆分为更小的编辑。 */
  @Test
  void rejectsAnOversizedDiff() {
    String replacement = "y".repeat(VfsEditDiff.MAX_DIFF_UTF8_BYTES);
    IllegalArgumentException rejection =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VfsEditDiff.unified(
                    "vfs:/sessions/s/big.txt", "token\n", "token", replacement, false));
    assertTrue(rejection.getMessage().contains("split this edit into smaller changes"));
  }
}
