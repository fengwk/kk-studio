package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

/** 验证 {@link CloudEditDiff} 的带行号 contextual diff 格式化、多处修改独立 hunk 划分以及超限自适应缩减行为。 */
class CloudEditDiffTest {

  @Test
  void formatDiffProducesLineNumberedOutput() {
    // 意图：编辑成功后应输出包含 path、revision、@@ hunk 标记和带行号的内容差异
    CloudPath path = CloudPath.of("/knowledge/app.py");
    String oldText = "line 1\nline 2\nval = 10\nline 4\nline 5";
    String newText = "line 1\nline 2\nval = 20\nline 4\nline 5";

    String diff = CloudEditDiff.formatDiff(path, 2, oldText, newText, "val = 10", "val = 20");

    assertTrue(diff.contains("path: /knowledge/app.py"));
    assertTrue(diff.contains("revision: 2"));
    assertTrue(diff.contains("@@ -1,5 +1,5 @@"));
    assertTrue(diff.contains("3|- val = 10"));
    assertTrue(diff.contains("3|+ val = 20"));
    assertTrue(diff.contains("1|  line 1"));
  }

  @Test
  void noChangeReturnsNoContentChanges() {
    // 意图：编辑前后无实质变动时应明确说明
    CloudPath path = CloudPath.of("/knowledge/same.txt");
    String text = "hello world";

    String diff = CloudEditDiff.formatDiff(path, 1, text, text, "hello", "hello");

    assertTrue(diff.contains("No content changes."));
  }

  @Test
  void multiOccurrenceEditsFarApartProduceSeparateHunksWithoutClaimingUnchangedLines() {
    // 意图：针对远距离的多处修改（replace_all=true），必须生成独立的 hunk，不得将数千行未变动代码误报为改动
    CloudPath path = CloudPath.of("/knowledge/multi.py");
    StringBuilder sbOld = new StringBuilder();
    for (int i = 1; i <= 1000; i++) {
      if (i == 10) {
        sbOld.append("port = 80\n");
      } else if (i == 950) {
        sbOld.append("port = 80\n");
      } else {
        sbOld.append("line_").append(i).append("\n");
      }
    }
    String oldContent = sbOld.toString();
    String newContent = oldContent.replace("port = 80", "port = 8080");

    String diff =
        CloudEditDiff.formatDiff(path, 2, oldContent, newContent, "port = 80", "port = 8080");

    assertTrue(diff.contains("10|- port = 80"));
    assertTrue(diff.contains("10|+ port = 8080"));
    assertTrue(diff.contains("950|- port = 80"));
    assertTrue(diff.contains("950|+ port = 8080"));
    // 验证中间第 500 行绝不会被误输出为删除或新增
    assertFalse(diff.contains("500|- line_500"));
    assertFalse(diff.contains("500|+ line_500"));
    assertFalse(diff.contains("line_500"));
    // 验证生成了至少两个独立的 hunk 头部
    int firstHunk = diff.indexOf("@@ -");
    int secondHunk = diff.indexOf("@@ -", firstHunk + 4);
    assertTrue(firstHunk >= 0 && secondHunk > firstHunk, "Must produce multiple distinct hunks");
  }

  @Test
  void largeDiffShrinksContextAndTruncatesSafelyWithoutSplittingSurrogates() {
    // 意图：超长差异输出时逐级缩小上下文并在达到上限时安全截断，保证 Unicode 代理对不被切断
    CloudPath path = CloudPath.of("/knowledge/large.txt");
    String emoji = "🚀";
    StringBuilder sbOld = new StringBuilder();
    StringBuilder sbNew = new StringBuilder();
    for (int i = 0; i < 1500; i++) {
      sbOld
          .append("old line ")
          .append(i)
          .append(" ")
          .append(emoji)
          .append(" verbose text to fill space\n");
      sbNew
          .append("new line ")
          .append(i)
          .append(" ")
          .append(emoji)
          .append(" verbose text to fill space\n");
    }

    String diff =
        CloudEditDiff.formatDiff(
            path, 5, sbOld.toString(), sbNew.toString(), "old line", "new line");

    assertTrue(diff.contains("path: /knowledge/large.txt"));
    assertTrue(diff.contains("[Note: context shrunk to 0 lines to keep within inline limits]"));
    assertTrue(
        diff.getBytes(StandardCharsets.UTF_8).length <= CloudEditDiff.MAX_DIFF_UTF8_BYTES,
        "Diff output must remain bounded below UTF-8 byte inline limit");
    // 验证截断后不会遗留畸形 surrogate
    assertDoesNotThrow(() -> StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(diff)));
  }

  @Test
  void sameLineRepeatedReplacementsCoalesceWithoutDuplication() {
    // 意图：验证单行内多处相同字符串替换时，正确合并行编辑区域，绝不重复输出同一行的删除与新增
    CloudPath path = CloudPath.of("/knowledge/same_line.js");
    String oldText = "const foo = 1; const foo = 2;\nsecond line\n";
    String newText = "const bar = 1; const bar = 2;\nsecond line\n";

    String diff = CloudEditDiff.formatDiff(path, 2, oldText, newText, "foo", "bar");

    assertTrue(diff.contains(" 1|- const foo = 1; const foo = 2;"));
    assertTrue(diff.contains(" 1|+ const bar = 1; const bar = 2;"));
    // 确保没有出现重复的第 1 行输出
    int firstMinus = diff.indexOf(" 1|-");
    int secondMinus = diff.indexOf(" 1|-", firstMinus + 1);
    assertTrue(firstMinus >= 0 && secondMinus < 0, "Line 1 deletion must appear exactly once");
    int firstPlus = diff.indexOf(" 1|+");
    int secondPlus = diff.indexOf(" 1|+", firstPlus + 1);
    assertTrue(firstPlus >= 0 && secondPlus < 0, "Line 1 addition must appear exactly once");
  }

  @Test
  void multilineInsertDeleteShiftsFormattedCorrectly() {
    // 意图：验证跨行展开/收缩导致后续行号偏移时，diff hunk 准确跟踪行号与前置/后置上下文
    CloudPath path = CloudPath.of("/knowledge/shift.txt");
    String oldText = "line1\ntarget_a\nline3\nline4\ntarget_b\nline6";
    // target_a 展开为 3 行，target_b 替换为单行
    String newText =
        "line1\ninserted_1\ninserted_2\ninserted_3\nline3\nline4\ntarget_b_updated\nline6";

    String diff =
        CloudEditDiff.formatDiff(
            path, 3, oldText, newText, "target_a", "inserted_1\ninserted_2\ninserted_3");

    assertTrue(diff.contains(" 2|- target_a"));
    assertTrue(diff.contains(" 2|+ inserted_1"));
    assertTrue(diff.contains(" 3|+ inserted_2"));
    assertTrue(diff.contains(" 4|+ inserted_3"));
    assertTrue(diff.contains(" 3|  line3"));
  }
}
