package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在保留文件表示形式（编码/BOM/未修改区域的行尾分隔符）的前提下执行确定性的精确文本替换。
 *
 * <p>{@code path} 为绝对路径时无需 {@code workdir}；为相对路径时必须提供本次调用的显式绝对 {@code workdir}，否则在执行前拒绝且不回退到
 * cwd、HOME 或任何会话默认目录。workdir 只用于解析路径，不是文件系统沙箱。
 *
 * <p>匹配与替换发生在 LF 归一视图上，未修改区域与替换引入的换行按既有行尾风格回填；无法在既有编码下无损表示的结果会被拒绝。结果文本先算出展示用的真实 行级
 * diff，再原子提交：提交之后不再有可失败步骤，因此已提交的修改不会被取消或展示失败误报为未写入。目录、设备、FIFO 等非普通文件在任何 I/O 之前拒绝。
 */
public final class EditCapability extends AbstractCodingCapability {

  /** diff 每个变更块两侧保留的上下文行数。 */
  private static final int DIFF_CONTEXT_LINES = 4;

  /** diff 行级对齐的单元格上限：超过时按“整段删除 + 整段新增”的真实行变化输出，保持有界内存。 */
  private static final int DIFF_MAX_CELLS = 2_000_000;

  /** 内联 diff 的字符上限。 */
  private static final int DIFF_MAX_CHARS = 30 * 1024;

  public EditCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String oldText = string(args, "old_string");
    String newText = string(args, "new_string");

    if (oldText.isEmpty()) {
      throw new IllegalArgumentException("old_string must not be empty");
    }

    String normalizedOld = normalizeToLf(oldText);
    String normalizedNew = normalizeToLf(newText);
    if (normalizedOld.equals(normalizedNew)) {
      throw new IllegalArgumentException(
          "No changes to apply: old_string and new_string must differ after line-ending"
              + " normalization");
    }

    String rawWorkdir = optionalString(args, "workdir");
    Path workdir = rawWorkdir == null ? null : EnvironmentPaths.workdir(rawWorkdir);
    Path path = EnvironmentPaths.existing(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a file: " + displayPath);
    }
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException("path is not a regular file: " + displayPath);
    }

    ReentrantLock lock = FileMutations.lock(path);
    try {
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }

      byte[] originalBytes = Files.readAllBytes(path);
      TextFileCodec.Decoded decoded = TextFileCodec.decode(originalBytes);
      String source = decoded.text();
      String lineEndingStyle = detectLineEnding(source);
      LineEndingDocument document = parseLineEndingDocument(source);
      List<Occurrence> occurrences = buildOccurrences(document, normalizedOld);

      if (occurrences.isEmpty()) {
        throw new IllegalArgumentException("Could not find old_string in " + displayPath);
      }
      boolean replaceAll = optionalBoolean(args, "replace_all");
      if (!replaceAll && occurrences.size() > 1) {
        throw new IllegalArgumentException(
            "Found "
                + occurrences.size()
                + " exact matches; use replace_all to change every match or provide more context");
      }
      if (replaceAll && hasOverlappingOccurrences(occurrences)) {
        throw new IllegalArgumentException(
            "Found overlapping exact matches for old_string in "
                + displayPath
                + "; replace_all cannot safely apply overlapping replacements");
      }

      LineEndingDocument nextDocument = document;
      List<Occurrence> pending =
          replaceAll ? new ArrayList<>(occurrences) : List.of(occurrences.getFirst());
      for (int index = pending.size() - 1; index >= 0; index--) {
        nextDocument =
            applyOccurrence(nextDocument, pending.get(index), normalizedNew, lineEndingStyle);
      }

      byte[] encoded =
          TextFileCodec.encode(
              serializeLineEndingDocument(nextDocument), decoded.charset(), decoded.bomLength());
      if (Arrays.equals(encoded, originalBytes)) {
        throw new IllegalArgumentException("No changes to apply: edit would not change the file");
      }

      // 真实行级 diff 只依赖内存中的文档：在提交前算好，提交后不再有可失败的展示步骤。
      String diffText = buildDiff(document, nextDocument);

      if (execution.isCancelled()) {
        throw new InterruptedException();
      }
      TextFileCommit.commit(path, encoded);

      String summary = "Edited " + displayPath + " successfully.\nReplacements: " + pending.size();
      return success(
          request.call().id(), diffText.isBlank() ? summary : summary + "\n\ndiff:\n" + diffText);
    } finally {
      lock.unlock();
    }
  }

  /** 比较规范化后的原文与结果文，输出带行号与位置感知上下文折叠的真实行级 diff。 */
  static String buildDiff(LineEndingDocument originalDoc, LineEndingDocument nextDoc) {
    List<String> originalLines = diffLines(originalDoc);
    List<String> nextLines = diffLines(nextDoc);
    return truncateInline(
        renderDiff(originalLines, nextLines, diffParts(originalLines, nextLines)));
  }

  /** 超过内联上限时截断；截断点必须落在代码点边界上，不得把代理对拆成孤立代理。 */
  static String truncateInline(String diff) {
    if (diff.length() <= DIFF_MAX_CHARS) {
      return diff;
    }
    int end = DIFF_MAX_CHARS;
    if (Character.isHighSurrogate(diff.charAt(end - 1))) {
      end--;
    }
    return diff.substring(0, end) + "\n... (diff truncated to fit inline limit)";
  }

  /**
   * diff 的比较与展示单元：行内容加其规范化行尾，因此“文件末尾换行有无”也算一次真实行变化。
   *
   * <p>以换行结尾的文件在末行之后还有一条空记录，它不代表内容行，比较与展示都不输出。
   */
  private static List<String> diffLines(LineEndingDocument document) {
    List<String> lines = document.lines();
    List<String> tokens = new ArrayList<>(lines.size());
    for (int index = 0; index < lines.size(); index++) {
      String ending = document.eolAfter().get(index);
      if (ending != null) {
        tokens.add(lines.get(index) + "\n");
        continue;
      }
      if (!lines.get(index).isEmpty()) {
        tokens.add(lines.get(index));
      }
    }
    return tokens;
  }

  private static String displayLine(String token) {
    return token.endsWith("\n") ? token.substring(0, token.length() - 1) : token;
  }

  /** 先裁掉共同前后缀，再对最小变更区域做行级对齐。 */
  private static List<DiffPart> diffParts(List<String> original, List<String> next) {
    int prefix = 0;
    while (prefix < original.size()
        && prefix < next.size()
        && original.get(prefix).equals(next.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < original.size() - prefix
        && suffix < next.size() - prefix
        && original.get(original.size() - 1 - suffix).equals(next.get(next.size() - 1 - suffix))) {
      suffix++;
    }
    List<DiffPart> parts = new ArrayList<>();
    if (prefix > 0) {
      parts.add(new DiffPart(DiffKind.CONTEXT, List.copyOf(original.subList(0, prefix))));
    }
    parts.addAll(
        alignMiddle(
            original.subList(prefix, original.size() - suffix),
            next.subList(prefix, next.size() - suffix)));
    if (suffix > 0) {
      parts.add(
          new DiffPart(
              DiffKind.CONTEXT,
              List.copyOf(original.subList(original.size() - suffix, original.size()))));
    }
    return parts;
  }

  /**
   * 用最长公共子序列把变更区域对齐成“上下文 / 删除 / 新增”序列。
   *
   * <p>单元格数超过 {@link #DIFF_MAX_CELLS} 时退化为整段删除加整段新增：内容仍是真实行，只是不再逐行对齐，用于约束最坏内存。
   */
  private static List<DiffPart> alignMiddle(List<String> original, List<String> next) {
    if (original.isEmpty() && next.isEmpty()) {
      return List.of();
    }
    if ((long) original.size() * next.size() > DIFF_MAX_CELLS) {
      return changedParts(original, next);
    }

    int[][] lcs = new int[original.size() + 1][next.size() + 1];
    for (int originalIndex = original.size() - 1; originalIndex >= 0; originalIndex--) {
      for (int nextIndex = next.size() - 1; nextIndex >= 0; nextIndex--) {
        lcs[originalIndex][nextIndex] =
            original.get(originalIndex).equals(next.get(nextIndex))
                ? lcs[originalIndex + 1][nextIndex + 1] + 1
                : Math.max(lcs[originalIndex + 1][nextIndex], lcs[originalIndex][nextIndex + 1]);
      }
    }

    List<DiffPart> parts = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> added = new ArrayList<>();
    List<String> context = new ArrayList<>();
    int originalIndex = 0;
    int nextIndex = 0;
    while (originalIndex < original.size() && nextIndex < next.size()) {
      if (original.get(originalIndex).equals(next.get(nextIndex))) {
        flushChanged(parts, removed, added);
        context.add(original.get(originalIndex));
        originalIndex++;
        nextIndex++;
        continue;
      }
      flushContext(parts, context);
      if (lcs[originalIndex + 1][nextIndex] >= lcs[originalIndex][nextIndex + 1]) {
        removed.add(original.get(originalIndex));
        originalIndex++;
      } else {
        added.add(next.get(nextIndex));
        nextIndex++;
      }
    }
    flushContext(parts, context);
    while (originalIndex < original.size()) {
      removed.add(original.get(originalIndex));
      originalIndex++;
    }
    while (nextIndex < next.size()) {
      added.add(next.get(nextIndex));
      nextIndex++;
    }
    flushChanged(parts, removed, added);
    return parts;
  }

  private static List<DiffPart> changedParts(List<String> original, List<String> next) {
    List<DiffPart> parts = new ArrayList<>(2);
    flushChanged(parts, new ArrayList<>(original), new ArrayList<>(next));
    return parts;
  }

  private static void flushContext(List<DiffPart> parts, List<String> context) {
    if (!context.isEmpty()) {
      parts.add(new DiffPart(DiffKind.CONTEXT, List.copyOf(context)));
      context.clear();
    }
  }

  private static void flushChanged(List<DiffPart> parts, List<String> removed, List<String> added) {
    if (!removed.isEmpty()) {
      parts.add(new DiffPart(DiffKind.REMOVED, List.copyOf(removed)));
      removed.clear();
    }
    if (!added.isEmpty()) {
      parts.add(new DiffPart(DiffKind.ADDED, List.copyOf(added)));
      added.clear();
    }
  }

  private static String renderDiff(List<String> original, List<String> next, List<DiffPart> parts) {
    int width =
        Math.max(2, String.valueOf(Math.max(1, Math.max(original.size(), next.size()))).length());
    DiffCursor cursor = new DiffCursor(width);
    StringBuilder diff = new StringBuilder();
    for (int index = 0; index < parts.size(); index++) {
      DiffPart part = parts.get(index);
      if (part.kind() != DiffKind.CONTEXT) {
        for (String line : part.lines()) {
          if (part.kind() == DiffKind.ADDED) {
            cursor.appendAdded(diff, displayLine(line));
          } else {
            cursor.appendRemoved(diff, displayLine(line));
          }
        }
        continue;
      }
      boolean previousChanged = index > 0 && parts.get(index - 1).kind() != DiffKind.CONTEXT;
      boolean nextChanged =
          index + 1 < parts.size() && parts.get(index + 1).kind() != DiffKind.CONTEXT;
      if (!previousChanged && !nextChanged) {
        cursor.skip(part.lines().size());
        continue;
      }
      ContextPosition position =
          previousChanged && nextChanged
              ? ContextPosition.INTER_HUNK
              : previousChanged ? ContextPosition.TRAILING : ContextPosition.LEADING;
      appendContextBlock(diff, part.lines(), cursor, position);
    }
    if (diff.length() > 0 && diff.charAt(diff.length() - 1) == '\n') {
      diff.setLength(diff.length() - 1);
    }
    return diff.toString();
  }

  /** 按上下文块与变更块的相对位置折叠：前导块保留最靠近后一个变更块的行，尾随块保留最靠近前一个变更块的行，变更块之间的块两侧各保留一半。 */
  private static void appendContextBlock(
      StringBuilder diff, List<String> block, DiffCursor cursor, ContextPosition position) {
    if (position == ContextPosition.TRAILING) {
      appendContextLines(diff, block, 0, Math.min(block.size(), DIFF_CONTEXT_LINES), cursor);
      if (block.size() > DIFF_CONTEXT_LINES) {
        diff.append("...\n");
        cursor.skip(block.size() - DIFF_CONTEXT_LINES);
      }
      return;
    }
    if (position == ContextPosition.LEADING) {
      if (block.size() <= DIFF_CONTEXT_LINES) {
        appendContextLines(diff, block, 0, block.size(), cursor);
        return;
      }
      cursor.skip(block.size() - DIFF_CONTEXT_LINES);
      diff.append("...\n");
      appendContextLines(diff, block, block.size() - DIFF_CONTEXT_LINES, block.size(), cursor);
      return;
    }
    if (block.size() <= 2 * DIFF_CONTEXT_LINES) {
      appendContextLines(diff, block, 0, block.size(), cursor);
      return;
    }
    appendContextLines(diff, block, 0, DIFF_CONTEXT_LINES, cursor);
    diff.append("...\n");
    cursor.skip(block.size() - 2 * DIFF_CONTEXT_LINES);
    appendContextLines(diff, block, block.size() - DIFF_CONTEXT_LINES, block.size(), cursor);
  }

  private static void appendContextLines(
      StringBuilder diff, List<String> block, int from, int to, DiffCursor cursor) {
    for (int index = from; index < to; index++) {
      cursor.appendContext(diff, displayLine(block.get(index)));
    }
  }

  private static String normalizeToLf(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  static String detectLineEnding(String text) {
    boolean hasCrlf = text.contains("\r\n");
    String withoutCrlf = text.replace("\r\n", "");
    boolean hasCr = withoutCrlf.contains("\r");
    boolean hasLf = withoutCrlf.contains("\n");
    int styles = (hasCrlf ? 1 : 0) + (hasCr ? 1 : 0) + (hasLf ? 1 : 0);
    if (styles > 1) {
      return "mixed";
    }
    if (hasCrlf) {
      return "\r\n";
    }
    if (hasCr) {
      return "\r";
    }
    return "\n";
  }

  private static LineEndingDocument parseLineEndingDocument(String text) {
    List<String> lines = new ArrayList<>();
    List<String> eolAfter = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (ch == '\r') {
        if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
          lines.add(current.toString());
          eolAfter.add("\r\n");
          current.setLength(0);
          index++;
          continue;
        }
        lines.add(current.toString());
        eolAfter.add("\r");
        current.setLength(0);
        continue;
      }
      if (ch == '\n') {
        lines.add(current.toString());
        eolAfter.add("\n");
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    lines.add(current.toString());
    eolAfter.add(null);
    return new LineEndingDocument(lines, eolAfter);
  }

  private static String serializeLineEndingDocument(LineEndingDocument document) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < document.lines().size(); index++) {
      builder.append(document.lines().get(index));
      String ending = document.eolAfter().get(index);
      if (ending != null) {
        builder.append(ending);
      }
    }
    return builder.toString();
  }

  private static String serializeNormalizedDocument(LineEndingDocument document) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < document.lines().size(); index++) {
      builder.append(document.lines().get(index));
      if (document.eolAfter().get(index) != null) {
        builder.append('\n');
      }
    }
    return builder.toString();
  }

  private static Cursor cursorFromNormalizedIndex(LineEndingDocument document, int index) {
    int offset = 0;
    for (int lineIndex = 0; lineIndex < document.lines().size(); lineIndex++) {
      String line = document.lines().get(lineIndex);
      if (index <= offset + line.length()) {
        return new Cursor(lineIndex, index - offset);
      }
      offset += line.length();
      if (document.eolAfter().get(lineIndex) != null) {
        if (index == offset + 1) {
          return new Cursor(Math.min(lineIndex + 1, document.lines().size() - 1), 0);
        }
        offset += 1;
      }
    }
    int lastLineIndex = Math.max(0, document.lines().size() - 1);
    String lastLine = document.lines().get(lastLineIndex);
    if (index == offset) {
      return new Cursor(lastLineIndex, lastLine.length());
    }
    throw new IllegalArgumentException("line ending document index is out of range");
  }

  private static List<Integer> occurrenceStarts(String text, String search) {
    List<Integer> starts = new ArrayList<>();
    int offset = 0;
    while ((offset = text.indexOf(search, offset)) >= 0) {
      starts.add(offset);
      offset += 1;
    }
    return starts;
  }

  private static List<Occurrence> buildOccurrences(LineEndingDocument document, String search) {
    String normalized = serializeNormalizedDocument(document);
    List<Occurrence> result = new ArrayList<>();
    for (int startIndex : occurrenceStarts(normalized, search)) {
      int endIndex = startIndex + search.length();
      Cursor start = cursorFromNormalizedIndex(document, startIndex);
      Cursor end = cursorFromNormalizedIndex(document, endIndex);
      List<String> consumedEndings = new ArrayList<>();
      for (int line = start.lineIndex(); line < end.lineIndex(); line++) {
        String ending = document.eolAfter().get(line);
        if (ending != null) {
          consumedEndings.add(ending);
        }
      }
      result.add(
          new Occurrence(
              startIndex,
              endIndex,
              start.lineIndex(),
              start.column(),
              end.lineIndex(),
              end.column(),
              consumedEndings,
              document.eolAfter().get(end.lineIndex())));
    }
    return result;
  }

  private static boolean hasOverlappingOccurrences(List<Occurrence> occurrences) {
    for (int index = 1; index < occurrences.size(); index++) {
      if (occurrences.get(index).startIndex() < occurrences.get(index - 1).endIndex()) {
        return true;
      }
    }
    return false;
  }

  private static String resolveInsertedEnding(
      String style, List<String> consumedEndings, int replacementEndingIndex) {
    if (!"mixed".equals(style)) {
      return style;
    }
    return replacementEndingIndex < consumedEndings.size()
        ? consumedEndings.get(replacementEndingIndex)
        : "\n";
  }

  private static LineEndingDocument applyOccurrence(
      LineEndingDocument document, Occurrence occurrence, String replacement, String style) {
    String prefix =
        document.lines().get(occurrence.startLine()).substring(0, occurrence.startColumn());
    String endLine = document.lines().get(occurrence.endLine());
    String suffix = endLine.substring(Math.min(occurrence.endColumn(), endLine.length()));
    LineEndingDocument replacementDocument = parseLineEndingDocument(replacement);
    List<String> replacementLines = new ArrayList<>(replacementDocument.lines());
    List<String> replacementEolAfter = new ArrayList<>(replacementDocument.eolAfter());
    int lastReplacementIndex = replacementLines.size() - 1;
    replacementLines.set(0, prefix + replacementLines.get(0));
    replacementLines.set(lastReplacementIndex, replacementLines.get(lastReplacementIndex) + suffix);
    for (int index = 0; index < replacementEolAfter.size(); index++) {
      if (index == replacementEolAfter.size() - 1) {
        replacementEolAfter.set(index, occurrence.trailingEnding());
      } else {
        replacementEolAfter.set(
            index, resolveInsertedEnding(style, occurrence.consumedEndings(), index));
      }
    }
    List<String> lines = new ArrayList<>();
    lines.addAll(document.lines().subList(0, occurrence.startLine()));
    lines.addAll(replacementLines);
    lines.addAll(document.lines().subList(occurrence.endLine() + 1, document.lines().size()));
    List<String> eolAfter = new ArrayList<>();
    eolAfter.addAll(document.eolAfter().subList(0, occurrence.startLine()));
    eolAfter.addAll(replacementEolAfter);
    eolAfter.addAll(
        document.eolAfter().subList(occurrence.endLine() + 1, document.eolAfter().size()));
    return new LineEndingDocument(lines, eolAfter);
  }

  /** diff 渲染游标：分开跟踪原文与结果文行号，并把编号右对齐到固定宽度。 */
  private static final class DiffCursor {

    private final int width;
    private int oldLine = 1;
    private int newLine = 1;

    private DiffCursor(int width) {
      this.width = width;
    }

    private void appendRemoved(StringBuilder diff, String line) {
      append(diff, '-', oldLine++, line);
    }

    private void appendAdded(StringBuilder diff, String line) {
      append(diff, '+', newLine++, line);
    }

    private void appendContext(StringBuilder diff, String line) {
      append(diff, ' ', oldLine, line);
      oldLine++;
      newLine++;
    }

    private void skip(int lines) {
      oldLine += lines;
      newLine += lines;
    }

    private void append(StringBuilder diff, char marker, int lineNumber, String line) {
      diff.append(marker).append(String.format("%" + width + "d|%s\n", lineNumber, line));
    }
  }

  private enum DiffKind {
    CONTEXT,
    REMOVED,
    ADDED
  }

  private enum ContextPosition {
    LEADING,
    INTER_HUNK,
    TRAILING
  }

  private record DiffPart(DiffKind kind, List<String> lines) {}

  record LineEndingDocument(List<String> lines, List<String> eolAfter) {}

  record Cursor(int lineIndex, int column) {}

  record Occurrence(
      int startIndex,
      int endIndex,
      int startLine,
      int startColumn,
      int endLine,
      int endColumn,
      List<String> consumedEndings,
      String trailingEnding) {}
}
