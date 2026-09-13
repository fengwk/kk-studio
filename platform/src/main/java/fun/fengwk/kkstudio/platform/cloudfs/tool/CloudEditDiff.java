package fun.fengwk.kkstudio.platform.cloudfs.tool;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 为 {@code cloud_edit} 生成有界、带行号的 contextual diff。
 *
 * <p>在 diff 过大时自适应收缩上下文行数（3 行 → 1 行 → 0 行），支持多处替换独立划分 diff hunk 避免将大段未变动代码误报为变更，并在极端情况下安全截断，严格控制在
 * inline 限制内。
 */
public final class CloudEditDiff {

  public static final int MAX_DIFF_UTF8_BYTES = 40 * 1024;

  private CloudEditDiff() {}

  /**
   * 生成带行号的有界 contextual diff 报告。
   *
   * @param path 编辑的文件虚拟绝对路径
   * @param newRevision 生成的新 revision
   * @param oldContent 编辑前的完整文本
   * @param newContent 编辑后的完整文本
   * @param oldString 替换的原子串
   * @param newString 替换后的新子串
   * @return 格式化的 diff 文本结果
   */
  public static String formatDiff(
      CloudPath path,
      long newRevision,
      String oldContent,
      String newContent,
      String oldString,
      String newString) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(oldContent, "oldContent");
    Objects.requireNonNull(newContent, "newContent");
    Objects.requireNonNull(oldString, "oldString");
    Objects.requireNonNull(newString, "newString");

    String prefix = "path: " + path + "\nrevision: " + newRevision + "\n\n";

    if (oldContent.equals(newContent)) {
      return prefix + "No content changes.";
    }

    // 尝试以 3 行上下文生成
    String diff3 = generateDiff(oldContent, newContent, oldString, newString, 3);
    String full3 = prefix + diff3;
    if (full3.getBytes(StandardCharsets.UTF_8).length <= MAX_DIFF_UTF8_BYTES) {
      return full3;
    }

    // 缩小到 1 行上下文
    String diff1 = generateDiff(oldContent, newContent, oldString, newString, 1);
    String full1 =
        prefix + "[Note: context shrunk to 1 line to keep within inline limits]\n\n" + diff1;
    if (full1.getBytes(StandardCharsets.UTF_8).length <= MAX_DIFF_UTF8_BYTES) {
      return full1;
    }

    // 缩小到 0 行上下文
    String diff0 = generateDiff(oldContent, newContent, oldString, newString, 0);
    String full0 =
        prefix + "[Note: context shrunk to 0 lines to keep within inline limits]\n\n" + diff0;
    if (full0.getBytes(StandardCharsets.UTF_8).length <= MAX_DIFF_UTF8_BYTES) {
      return full0;
    }

    // 仍然超长时按 UTF-8 字节预算截断，保证 Unicode 安全
    return truncateDiff(full0, MAX_DIFF_UTF8_BYTES);
  }

  private static String truncateDiff(String report, int maxBytes) {
    String marker = "\n... [diff truncated to keep within inline limit]";
    byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
    int targetBudget = maxBytes - markerBytes.length;
    if (targetBudget <= 0) {
      return marker;
    }
    StringBuilder sb = new StringBuilder();
    int currentBytes = 0;
    int i = 0;
    while (i < report.length()) {
      int cp = report.codePointAt(i);
      int charCount = Character.charCount(cp);
      int cpBytes = (new String(Character.toChars(cp))).getBytes(StandardCharsets.UTF_8).length;
      if (currentBytes + cpBytes > targetBudget) {
        break;
      }
      sb.appendCodePoint(cp);
      currentBytes += cpBytes;
      i += charCount;
    }
    return sb.toString() + marker;
  }

  private static String generateDiff(
      String oldContent, String newContent, String oldString, String newString, int contextLines) {
    CloudSearchSupport.TextLines oldLinesObj = CloudSearchSupport.TextLines.from(oldContent);
    CloudSearchSupport.TextLines newLinesObj = CloudSearchSupport.TextLines.from(newContent);

    List<CloudSearchSupport.TextLine> oldLines = oldLinesObj.lines();
    List<CloudSearchSupport.TextLine> newLines = newLinesObj.lines();

    if (oldLines.isEmpty() && newLines.isEmpty()) {
      return "No content changes.";
    }

    List<EditRegion> regions =
        findEditRegions(oldContent, newContent, oldString, newString, oldLines, newLines);
    if (regions.isEmpty()) {
      return "No content changes.";
    }

    // 将相距在 2 * contextLines 之内的区域合并为同一个 hunk
    List<Hunk> hunks = mergeRegionsToHunks(regions, oldLines.size(), newLines.size(), contextLines);
    int maxLineNum = Math.max(oldLines.size(), newLines.size());
    int width = Math.max(1, String.valueOf(maxLineNum).length());

    StringBuilder sb = new StringBuilder();
    for (int h = 0; h < hunks.size(); h++) {
      if (h > 0) {
        sb.append("\n");
      }
      Hunk hunk = hunks.get(h);
      int oldCount = hunk.oldEndLine - hunk.oldStartLine + 1;
      int newCount = hunk.newEndLine - hunk.newStartLine + 1;
      sb.append(
          String.format(
              "@@ -%d,%d +%d,%d @@\n",
              hunk.oldStartLine + 1, oldCount, hunk.newStartLine + 1, newCount));

      int curOld = hunk.oldStartLine;
      for (EditRegion reg : hunk.regions) {
        // 输出前置上下文
        while (curOld < reg.oldStartLine) {
          sb.append(
              String.format(" %" + width + "d|  %s\n", curOld + 1, oldLines.get(curOld).content()));
          curOld++;
        }
        // 输出被删除的原行
        for (int i = reg.oldStartLine; i <= reg.oldEndLine; i++) {
          sb.append(String.format(" %" + width + "d|- %s\n", i + 1, oldLines.get(i).content()));
        }
        // 输出新增的替换行
        for (int i = reg.newStartLine; i <= reg.newEndLine; i++) {
          sb.append(String.format(" %" + width + "d|+ %s\n", i + 1, newLines.get(i).content()));
        }
        curOld = reg.oldEndLine + 1;
      }
      // 输出后置上下文
      while (curOld <= hunk.oldEndLine) {
        sb.append(
            String.format(" %" + width + "d|  %s\n", curOld + 1, oldLines.get(curOld).content()));
        curOld++;
      }
    }

    return sb.toString().trim();
  }

  private static List<EditRegion> findEditRegions(
      String oldContent,
      String newContent,
      String oldString,
      String newString,
      List<CloudSearchSupport.TextLine> oldLines,
      List<CloudSearchSupport.TextLine> newLines) {
    List<EditRegion> regions = new ArrayList<>();
    if (oldString.isEmpty()) {
      return regions;
    }

    List<Integer> occurrences = new ArrayList<>();
    int pos = 0;
    while ((pos = oldContent.indexOf(oldString, pos)) >= 0) {
      occurrences.add(pos);
      pos += oldString.length();
    }

    if (occurrences.isEmpty()) {
      return regions;
    }

    int delta = newString.length() - oldString.length();
    for (int k = 0; k < occurrences.size(); k++) {
      int oldStartChar = occurrences.get(k);
      int oldEndChar = oldStartChar + oldString.length();
      int newStartChar = oldStartChar + k * delta;
      int newEndChar = newStartChar + newString.length();

      int oldStartLine = findLineByChar(oldLines, oldStartChar);
      int oldEndLine = findLineByChar(oldLines, Math.max(oldStartChar, oldEndChar - 1));
      int newStartLine = findLineByChar(newLines, newStartChar);
      int newEndLine = findLineByChar(newLines, Math.max(newStartChar, newEndChar - 1));

      regions.add(new EditRegion(oldStartLine, oldEndLine, newStartLine, newEndLine));
    }

    // 合并在原文本或新文本行范围发生重叠的编辑区域，杜绝单行多处替换时重复输出整行
    List<EditRegion> coalesced = new ArrayList<>();
    EditRegion cur = regions.get(0);
    for (int i = 1; i < regions.size(); i++) {
      EditRegion next = regions.get(i);
      if (next.oldStartLine <= cur.oldEndLine || next.newStartLine <= cur.newEndLine) {
        cur =
            new EditRegion(
                Math.min(cur.oldStartLine, next.oldStartLine),
                Math.max(cur.oldEndLine, next.oldEndLine),
                Math.min(cur.newStartLine, next.newStartLine),
                Math.max(cur.newEndLine, next.newEndLine));
      } else {
        coalesced.add(cur);
        cur = next;
      }
    }
    coalesced.add(cur);
    return coalesced;
  }

  private static int findLineByChar(List<CloudSearchSupport.TextLine> lines, int charOffset) {
    if (lines.isEmpty()) {
      return 0;
    }
    int low = 0;
    int high = lines.size() - 1;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (lines.get(mid).startCharOffset() <= charOffset) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  private static List<Hunk> mergeRegionsToHunks(
      List<EditRegion> regions, int oldLineCount, int newLineCount, int contextLines) {
    List<Hunk> hunks = new ArrayList<>();
    if (regions.isEmpty()) {
      return hunks;
    }

    Hunk current = createHunk(regions.get(0), oldLineCount, newLineCount, contextLines);
    for (int i = 1; i < regions.size(); i++) {
      EditRegion reg = regions.get(i);
      int nextOldStart = Math.max(0, reg.oldStartLine - contextLines);
      if (nextOldStart <= current.oldEndLine + 1) {
        // 重叠或相邻，合并进当前 hunk
        current.regions.add(reg);
        current.oldEndLine = Math.min(oldLineCount - 1, reg.oldEndLine + contextLines);
        int trailingCtx = current.oldEndLine - reg.oldEndLine;
        current.newEndLine = Math.min(newLineCount - 1, reg.newEndLine + trailingCtx);
      } else {
        hunks.add(current);
        current = createHunk(reg, oldLineCount, newLineCount, contextLines);
      }
    }
    hunks.add(current);
    return hunks;
  }

  private static Hunk createHunk(
      EditRegion reg, int oldLineCount, int newLineCount, int contextLines) {
    Hunk hunk = new Hunk();
    hunk.regions.add(reg);
    hunk.oldStartLine = Math.max(0, reg.oldStartLine - contextLines);
    hunk.oldEndLine = Math.min(oldLineCount - 1, reg.oldEndLine + contextLines);
    int leadingCtx = reg.oldStartLine - hunk.oldStartLine;
    int trailingCtx = hunk.oldEndLine - reg.oldEndLine;
    hunk.newStartLine = Math.max(0, reg.newStartLine - leadingCtx);
    hunk.newEndLine = Math.min(newLineCount - 1, reg.newEndLine + trailingCtx);
    return hunk;
  }

  private record EditRegion(int oldStartLine, int oldEndLine, int newStartLine, int newEndLine) {}

  private static class Hunk {
    int oldStartLine;
    int oldEndLine;
    int newStartLine;
    int newEndLine;
    final List<EditRegion> regions = new ArrayList<>();
  }
}
