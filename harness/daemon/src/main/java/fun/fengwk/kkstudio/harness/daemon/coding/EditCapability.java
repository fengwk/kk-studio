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

/** 在保留文件表示形式（编码/BOM/未修改区域的行尾分隔符）的前提下执行确定性的精确文本替换。 */
public final class EditCapability extends AbstractCodingCapability {

  public EditCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_APPLY_EDIT));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String oldText = string(args, "old_string");
    String newText = string(args, "new_string");
    String normalizedOld = normalizeToLf(oldText);
    String normalizedNew = normalizeToLf(newText);
    if (normalizedOld.equals(normalizedNew)) {
      throw new IllegalArgumentException(
          "No changes to apply: old_string and new_string must differ after line-ending"
              + " normalization");
    }
    if (oldText.isEmpty()) {
      throw new IllegalArgumentException("old_string must not be empty");
    }
    Path path =
        EnvironmentPaths.existing(rawPath, EnvironmentPaths.workdir(string(args, "workdir")));
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a file: " + rawPath);
    }
    ReentrantLock lock = FileMutations.lock(path);
    try {
      byte[] originalBytes = Files.readAllBytes(path);
      TextFileCodec.Decoded decoded = TextFileCodec.decode(originalBytes);
      String source = decoded.text();
      String lineEndingStyle = detectLineEnding(source);
      LineEndingDocument document = parseLineEndingDocument(source);
      List<Occurrence> occurrences = buildOccurrences(document, normalizedOld);
      if (occurrences.isEmpty()) {
        throw new IllegalArgumentException("old_string was not found in file");
      }
      boolean replaceAll = optionalBoolean(args, "replace_all");
      if (!replaceAll && occurrences.size() > 1) {
        throw new IllegalArgumentException(
            "Found "
                + occurrences.size()
                + " exact matches; use replace_all to change every match");
      }
      if (replaceAll && hasOverlappingOccurrences(occurrences)) {
        throw new IllegalArgumentException(
            "Found overlapping exact matches for old_string in "
                + rawPath
                + "; replace_all cannot safely apply overlapping replacements");
      }
      LineEndingDocument nextDocument = document;
      List<Occurrence> pending =
          replaceAll ? new ArrayList<>(occurrences) : List.of(occurrences.getFirst());
      for (int index = pending.size() - 1; index >= 0; index--) {
        nextDocument =
            applyOccurrence(nextDocument, pending.get(index), normalizedNew, lineEndingStyle);
      }
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }
      byte[] encoded =
          TextFileCodec.encode(
              serializeLineEndingDocument(nextDocument), decoded.charset(), decoded.bomLength());
      if (Arrays.equals(encoded, originalBytes)) {
        throw new IllegalArgumentException("No changes to apply: edit would not change the file");
      }
      Files.write(path, encoded);
      int replacements = replaceAll ? occurrences.size() : 1;
      return success(
          request.call().id(),
          "Edited "
              + rawPath
              + " successfully.\nReplacements: "
              + replacements
              + "\n\ndiff:\n-"
              + summary(oldText)
              + "\n+"
              + summary(newText));
    } finally {
      lock.unlock();
    }
  }

  /** LF/CRLF/CR 统一为 LF，用于在归一化空间匹配 old_string 并比较 new_string。 */
  private static String normalizeToLf(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  /** 检测既有行尾样式：统一 CRLF、统一 LF、统一 CR 或 mixed；不做整文件规范化。 */
  private static String detectLineEnding(String text) {
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

  /** 行级文本表示：内容行与每行后的既有分隔符（null 表示文件尾无换行）。 */
  private record LineEndingDocument(List<String> lines, List<String> eolAfter) {}

  /** occurrence 在归一化文本中的区间、行光标以及被消费/尾随的分隔符。 */
  private record Occurrence(
      int startIndex,
      int endIndex,
      int startLine,
      int startColumn,
      int endLine,
      int endColumn,
      List<String> consumedEndings,
      String trailingEnding) {}

  private record Cursor(int lineIndex, int column) {}

  private static LineEndingDocument parseLineEndingDocument(String content) {
    List<String> lines = new ArrayList<>();
    List<String> eolAfter = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int index = 0; index < content.length(); index++) {
      char ch = content.charAt(index);
      if (ch == '\r') {
        String ending =
            index + 1 < content.length() && content.charAt(index + 1) == '\n' ? "\r\n" : "\r";
        if ("\r\n".equals(ending)) {
          index++;
        }
        lines.add(current.toString());
        eolAfter.add(ending);
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

  /** 每个起点都探测一次，重叠 occurrence 同样计入唯一性判断。 */
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

  /** mixed 文件中新增换行沿用被替换段的行尾；无对应时回退 LF。 */
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

  private static String summary(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000) + "... (diff text truncated)";
  }
}
