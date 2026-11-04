package fun.fengwk.kkstudio.platform.vfs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 由 VFS 生成的编辑审查 diff：只覆盖被替换的位置及其上下文，不重排或重写文件的其它部分。
 *
 * <p>输出是标准 unified diff 的子集（{@code ---}/{@code +++} 文件头 + 逐块 hunk），用于审批时展示真实变化；它不参与执行，执行仍按 revision
 * CAS 写入。
 */
final class VfsEditDiff {

  static final int MAX_DIFF_UTF8_BYTES = 256 * 1024;

  private static final int CONTEXT_LINES = 3;
  private static final int MAX_HUNKS = 20;

  private VfsEditDiff() {}

  /**
   * 生成 {@code oldText -> newText} 的审批 diff。
   *
   * @param displayPath 用户可见的 vfs 路径
   * @param oldText 当前文件全文
   * @param oldString 被替换的原文（在 {@code oldText} 中出现一次或多次）
   * @param newString 替换后的文本
   * @param replaceAll 是否替换全部出现
   * @throws IllegalArgumentException diff 超过审查体积上限
   */
  static String unified(
      String displayPath, String oldText, String oldString, String newString, boolean replaceAll) {
    List<Integer> starts = occurrences(oldText, oldString);
    if (starts.isEmpty()) {
      throw new IllegalArgumentException("edit old_string was not found in " + displayPath);
    }
    List<String> oldLines = lines(oldText);
    List<String> removedLines = lines(oldString);
    List<String> addedLines = lines(newString);

    StringBuilder out = new StringBuilder();
    out.append("--- ").append(displayPath).append('\n');
    out.append("+++ ").append(displayPath).append('\n');

    int lineDelta = 0;
    // 前一个 hunk 已展示到的旧文件行尾（不含）：后续 hunk 不重复展示同一段上下文，避免重叠的非法 hunk。
    int previousOldEnd = 0;
    int hunks = Math.min(starts.size(), MAX_HUNKS);
    for (int index = 0; index < hunks; index++) {
      int oldLine = lineIndexAt(oldText, starts.get(index));
      int before = Math.min(CONTEXT_LINES, Math.max(0, oldLine - previousOldEnd));
      int afterStart = oldLine + removedLines.size();
      int after = Math.min(CONTEXT_LINES, Math.max(0, oldLines.size() - afterStart));
      previousOldEnd = afterStart + after;
      int oldStart = oldLine - before + 1;
      int newStart = oldStart + lineDelta;
      int oldCount = before + removedLines.size() + after;
      int newCount = before + addedLines.size() + after;

      out.append("@@ -")
          .append(oldStart)
          .append(',')
          .append(oldCount)
          .append(" +")
          .append(newStart)
          .append(',')
          .append(newCount)
          .append(" @@\n");
      for (int line = oldLine - before; line < oldLine; line++) {
        out.append(' ').append(oldLines.get(line)).append('\n');
      }
      for (String line : removedLines) {
        out.append('-').append(line).append('\n');
      }
      for (String line : addedLines) {
        out.append('+').append(line).append('\n');
      }
      for (int line = afterStart; line < afterStart + after; line++) {
        out.append(' ').append(oldLines.get(line)).append('\n');
      }
      lineDelta += addedLines.size() - removedLines.size();
    }
    if (starts.size() > hunks) {
      out.append("… ")
          .append(starts.size() - hunks)
          .append(" more replacement")
          .append(starts.size() - hunks == 1 ? "" : "s")
          .append(" not shown\n");
    }
    if (out.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DIFF_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "edit review exceeds "
              + MAX_DIFF_UTF8_BYTES
              + " bytes; split this edit into smaller changes");
    }
    return out.toString();
  }

  private static List<Integer> occurrences(String text, String needle) {
    List<Integer> starts = new ArrayList<>();
    int from = 0;
    while (true) {
      int found = text.indexOf(needle, from);
      if (found < 0) {
        return starts;
      }
      starts.add(found);
      from = found + needle.length();
    }
  }

  /** 按行拆分；结尾换行不产生额外的空行。 */
  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return lines;
  }

  private static int lineIndexAt(String text, int offset) {
    int line = 0;
    for (int index = 0; index < offset; index++) {
      if (text.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
  }
}
