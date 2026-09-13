package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 负责 Cloud Grep 的 RE2/J 正则与字面量搜索、行索引定位以及基于真实 Match 的 500 code point 有界摘录提取。 */
public final class CloudSearchSupport {

  public static final int MAX_LINE_CODE_POINTS = 500;

  private CloudSearchSupport() {}

  /** 搜索命中行结果。 */
  public record GrepMatch(int lineNumber, String excerpt) {}

  /** 编译 RE2 模式；语法错误时严禁在异常消息与 cause 中回显用户 pattern。 */
  public static Pattern compileRe2(String pattern, boolean ignoreCase, boolean multiline) {
    Objects.requireNonNull(pattern, "pattern");
    int flags = 0;
    if (ignoreCase) {
      flags |= Pattern.CASE_INSENSITIVE;
    }
    if (multiline) {
      flags |= Pattern.MULTILINE;
    }
    try {
      return Pattern.compile(pattern, flags);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regular expression pattern");
    }
  }

  public static List<GrepMatch> searchFile(
      String fileText,
      String pattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      SearchControl control)
      throws InterruptedException {
    return searchFile(
        fileText, pattern, literal, ignoreCase, multiline, Integer.MAX_VALUE, control);
  }

  public static List<GrepMatch> searchFile(
      String fileText,
      String pattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      int maxMatches,
      SearchControl control)
      throws InterruptedException {
    if (maxMatches <= 0) {
      return List.of();
    }
    if (literal) {
      return searchLiteral(fileText, pattern, ignoreCase, multiline, maxMatches, control);
    } else {
      Pattern re2Pattern = compileRe2(pattern, ignoreCase, multiline);
      return searchFile(fileText, re2Pattern, multiline, maxMatches, control);
    }
  }

  public static List<GrepMatch> searchFile(
      String fileText, Pattern re2Pattern, boolean multiline, int maxMatches, SearchControl control)
      throws InterruptedException {
    Objects.requireNonNull(fileText, "fileText");
    Objects.requireNonNull(re2Pattern, "re2Pattern");
    Objects.requireNonNull(control, "control");
    if (maxMatches <= 0) {
      return List.of();
    }

    TextLines textLines = TextLines.from(fileText);
    if (textLines.lines().isEmpty()) {
      return List.of();
    }
    return searchRegex(textLines, re2Pattern, multiline, maxMatches, control);
  }

  public static List<GrepMatch> searchLiteral(
      String fileText,
      String pattern,
      boolean ignoreCase,
      boolean multiline,
      int maxMatches,
      SearchControl control)
      throws InterruptedException {
    Objects.requireNonNull(fileText, "fileText");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(control, "control");
    if (maxMatches <= 0) {
      return List.of();
    }

    TextLines textLines = TextLines.from(fileText);
    if (textLines.lines().isEmpty()) {
      return List.of();
    }
    return searchLiteral(textLines, pattern, ignoreCase, multiline, maxMatches, control);
  }

  private static List<GrepMatch> searchLiteral(
      TextLines textLines,
      String pattern,
      boolean ignoreCase,
      boolean multiline,
      int maxMatches,
      SearchControl control)
      throws InterruptedException {
    if (pattern.isEmpty()) {
      return List.of();
    }

    List<TextLine> lines = textLines.lines();
    if (!multiline) {
      List<GrepMatch> results = new ArrayList<>(Math.min(maxMatches, lines.size()));
      for (int i = 0; i < lines.size(); i++) {
        control.check();
        TextLine line = lines.get(i);
        int idx = findSubstring(line.content(), pattern, ignoreCase, control);
        if (idx >= 0) {
          int cpStart = line.content().codePointCount(0, idx);
          int cpEnd = line.content().codePointCount(0, idx + pattern.length());
          String excerpt = buildExcerpt(line.content(), cpStart, cpEnd);
          results.add(new GrepMatch(i + 1, excerpt));
          if (results.size() >= maxMatches) {
            break;
          }
        }
      }
      return results;
    }

    // multiline = true
    String fullText = textLines.normalizedText();
    boolean[] matchedLines = new boolean[lines.size()];
    int[] matchCpStarts = new int[lines.size()];
    int[] matchCpEnds = new int[lines.size()];
    Arrays.fill(matchCpStarts, -1);
    Arrays.fill(matchCpEnds, -1);
    int matchedCount = 0;

    int pos = 0;
    while (pos <= fullText.length() - pattern.length()) {
      control.check();
      int idx = findSubstringFrom(fullText, pattern, ignoreCase, pos, control);
      if (idx < 0) {
        break;
      }
      int matchStartChar = idx;
      int matchEndChar = idx + pattern.length();
      int newlyCovered =
          markCoveredLines(
              lines, matchStartChar, matchEndChar, matchedLines, matchCpStarts, matchCpEnds);
      matchedCount += newlyCovered;
      if (matchedCount >= maxMatches) {
        break;
      }
      pos = idx + Math.max(1, pattern.length());
    }

    List<GrepMatch> results = new ArrayList<>(Math.min(maxMatches, matchedCount));
    for (int i = 0; i < lines.size() && results.size() < maxMatches; i++) {
      if (matchedLines[i]) {
        TextLine line = lines.get(i);
        int cpStart = Math.max(0, matchCpStarts[i]);
        int cpEnd = matchCpEnds[i] >= cpStart ? matchCpEnds[i] : cpStart;
        String excerpt = buildExcerpt(line.content(), cpStart, cpEnd);
        results.add(new GrepMatch(i + 1, excerpt));
      }
    }
    return results;
  }

  private static List<GrepMatch> searchRegex(
      TextLines textLines,
      Pattern pattern,
      boolean multiline,
      int maxMatches,
      SearchControl control)
      throws InterruptedException {
    List<TextLine> lines = textLines.lines();
    if (!multiline) {
      List<GrepMatch> results = new ArrayList<>(Math.min(maxMatches, lines.size()));
      for (int i = 0; i < lines.size(); i++) {
        control.check();
        TextLine line = lines.get(i);
        Matcher matcher = pattern.matcher(line.content());
        if (matcher.find()) {
          int cpStart = line.content().codePointCount(0, matcher.start());
          int cpEnd = line.content().codePointCount(0, matcher.end());
          String excerpt = buildExcerpt(line.content(), cpStart, cpEnd);
          results.add(new GrepMatch(i + 1, excerpt));
          if (results.size() >= maxMatches) {
            break;
          }
        }
      }
      return results;
    }

    // multiline = true
    String fullText = textLines.normalizedText();
    boolean[] matchedLines = new boolean[lines.size()];
    int[] matchCpStarts = new int[lines.size()];
    int[] matchCpEnds = new int[lines.size()];
    Arrays.fill(matchCpStarts, -1);
    Arrays.fill(matchCpEnds, -1);
    int matchedCount = 0;

    Matcher matcher = pattern.matcher(fullText);
    while (matcher.find()) {
      control.check();
      int matchStartChar = matcher.start();
      int matchEndChar = matcher.end();
      int newlyCovered =
          markCoveredLines(
              lines, matchStartChar, matchEndChar, matchedLines, matchCpStarts, matchCpEnds);
      matchedCount += newlyCovered;
      if (matchedCount >= maxMatches) {
        break;
      }
    }

    List<GrepMatch> results = new ArrayList<>(Math.min(maxMatches, matchedCount));
    for (int i = 0; i < lines.size() && results.size() < maxMatches; i++) {
      if (matchedLines[i]) {
        TextLine line = lines.get(i);
        int cpStart = Math.max(0, matchCpStarts[i]);
        int cpEnd = matchCpEnds[i] >= cpStart ? matchCpEnds[i] : cpStart;
        String excerpt = buildExcerpt(line.content(), cpStart, cpEnd);
        results.add(new GrepMatch(i + 1, excerpt));
      }
    }
    return results;
  }

  private static int markCoveredLines(
      List<TextLine> lines,
      int matchStartChar,
      int matchEndChar,
      boolean[] matchedLines,
      int[] matchCpStarts,
      int[] matchCpEnds) {
    int startLine = findLineIndex(lines, matchStartChar);
    int coveredEnd = matchEndChar > matchStartChar ? matchEndChar - 1 : matchStartChar;
    int endLine = findLineIndex(lines, coveredEnd);
    int newlyCovered = 0;

    for (int lineIdx = startLine; lineIdx <= endLine && lineIdx < lines.size(); lineIdx++) {
      if (!matchedLines[lineIdx]) {
        matchedLines[lineIdx] = true;
        newlyCovered++;
      }
      TextLine line = lines.get(lineIdx);
      int lineStartChar = line.startCharOffset();
      int lineEndChar = line.endCharOffset();

      int overlapStartChar = Math.max(lineStartChar, matchStartChar);
      int overlapEndChar = Math.min(lineEndChar, matchEndChar);
      if (overlapEndChar < overlapStartChar) {
        overlapEndChar = overlapStartChar;
      }

      int relStartChar = overlapStartChar - lineStartChar;
      int relEndChar = overlapEndChar - lineStartChar;

      int cpStart = line.content().codePointCount(0, relStartChar);
      int cpEnd = line.content().codePointCount(0, relEndChar);

      if (matchCpStarts[lineIdx] < 0) {
        matchCpStarts[lineIdx] = cpStart;
        matchCpEnds[lineIdx] = cpEnd;
      }
    }
    return newlyCovered;
  }

  private static int findLineIndex(List<TextLine> lines, int charOffset) {
    int bounded = Math.max(0, charOffset);
    int low = 0;
    int high = lines.size() - 1;
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (lines.get(mid).startCharOffset() <= bounded) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  private static final int SUBSTRING_CHECK_INTERVAL = 4096;

  private static int findSubstring(
      String source, String target, boolean ignoreCase, SearchControl control)
      throws InterruptedException {
    return findSubstringFrom(source, target, ignoreCase, 0, control);
  }

  private static int findSubstringFrom(
      String source, String target, boolean ignoreCase, int fromIndex, SearchControl control)
      throws InterruptedException {
    int max = source.length() - target.length();
    int start = Math.max(0, fromIndex);
    for (int i = start; i <= max; i++) {
      if ((i - start) > 0 && (i - start) % SUBSTRING_CHECK_INTERVAL == 0 && control != null) {
        control.check();
      }
      if (source.regionMatches(ignoreCase, i, target, 0, target.length())) {
        return i;
      }
    }
    return -1;
  }

  /**
   * 围绕实际 match 生成最多 500 Unicode code point 的摘录（包含任何前后省略与截断标记）， 居中显示 actual match，且杜绝分割 Unicode
   * surrogate pairs。
   *
   * @param lineContent 行原始内容
   * @param matchCpStart match 在本行的起始 code point 偏移量
   * @param matchCpEnd match 在本行的结束 code point 偏移量
   * @return 严格 <= 500 code points 的摘录内容
   */
  public static String buildExcerpt(String lineContent, int matchCpStart, int matchCpEnd) {
    int[] cps = lineContent.codePoints().toArray();
    int totalCps = cps.length;
    if (totalCps <= MAX_LINE_CODE_POINTS) {
      return lineContent;
    }

    String endMarker = "... (line truncated to 500 chars)";
    String startMarker = "...";

    matchCpStart = Math.max(0, Math.min(matchCpStart, totalCps));
    matchCpEnd = Math.max(matchCpStart, Math.min(matchCpEnd, totalCps));
    int matchLen = matchCpEnd - matchCpStart;

    int winStart;
    int winEnd;

    // 当使用 startMarker 与 endMarker 时，内容预算为 500 - 3 - 33 = 464 code points；
    // 当不使用 startMarker 时，内容预算为 500 - 33 = 467 code points。
    if (matchLen >= 464) {
      winStart = Math.max(0, Math.min(matchCpStart, totalCps - 464));
      if (winStart == 0) {
        winEnd = Math.min(totalCps, 467);
        return new String(cps, 0, winEnd) + endMarker;
      }
      winEnd = Math.min(totalCps, winStart + 464);
      return startMarker + new String(cps, winStart, winEnd - winStart) + endMarker;
    }

    int center = (matchCpStart + matchCpEnd) / 2;
    winStart = center - (464 / 2);
    if (winStart <= 0) {
      winStart = 0;
      winEnd = Math.min(totalCps, 467);
      return new String(cps, 0, winEnd) + endMarker;
    }
    if (winStart + 464 > totalCps) {
      winStart = Math.max(0, totalCps - 464);
    }
    if (winStart == 0) {
      winEnd = Math.min(totalCps, 467);
      return new String(cps, 0, winEnd) + endMarker;
    }
    winEnd = Math.min(totalCps, winStart + 464);
    return startMarker + new String(cps, winStart, winEnd - winStart) + endMarker;
  }

  public record TextLine(int startCharOffset, int endCharOffset, String content) {}

  public record TextLines(String normalizedText, List<TextLine> lines) {

    public static TextLines from(String source) {
      String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
      if (normalized.isEmpty()) {
        return new TextLines(normalized, List.of());
      }
      String[] contents = normalized.split("\n", -1);
      int count = normalized.endsWith("\n") ? contents.length - 1 : contents.length;
      List<TextLine> lines = new ArrayList<>(count);
      int offset = 0;
      for (int i = 0; i < count; i++) {
        String lineContent = contents[i];
        lines.add(new TextLine(offset, offset + lineContent.length(), lineContent));
        offset += lineContent.length() + 1;
      }
      return new TextLines(normalized, List.copyOf(lines));
    }
  }
}
