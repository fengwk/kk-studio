package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/** 在保留文件表示形式（编码/BOM/未修改区域的行尾分隔符）的前提下执行确定性的精确文本替换。 */
public final class EditCapability extends AbstractCodingCapability {

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

    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a file: " + displayPath);
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

      if (execution.isCancelled()) {
        throw new InterruptedException();
      }

      byte[] encoded =
          TextFileCodec.encode(
              serializeLineEndingDocument(nextDocument), decoded.charset(), decoded.bomLength());
      if (Arrays.equals(encoded, originalBytes)) {
        throw new IllegalArgumentException("No changes to apply: edit would not change the file");
      }

      String diffText = buildContextualDiff(document, nextDocument, pending, normalizedNew);

      Path parent = Objects.requireNonNull(path.getParent(), "path must have a parent");
      Path temp = Files.createTempFile(parent, ".kk-edit-", ".tmp");
      try {
        Files.write(temp, encoded);
        try {
          Files.move(
              temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }

      return success(
          request.call().id(),
          "Edited "
              + displayPath
              + " successfully.\nReplacements: "
              + pending.size()
              + "\n\ndiff:\n"
              + diffText);
    } finally {
      lock.unlock();
    }
  }

  static String buildContextualDiff(
      LineEndingDocument originalDoc,
      LineEndingDocument nextDoc,
      List<Occurrence> occurrences,
      String normalizedNew) {
    if (occurrences.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    int totalOriginalLines = originalDoc.lines().size();
    int totalNextLines = nextDoc.lines().size();
    int width = Math.max(2, String.valueOf(Math.max(totalOriginalLines, totalNextLines)).length());

    List<Occurrence> sorted = new ArrayList<>(occurrences);
    sorted.sort(Comparator.comparingInt(Occurrence::startLine));

    int lastPrintedOriginalLine = -1;

    for (int i = 0; i < sorted.size(); i++) {
      Occurrence occ = sorted.get(i);
      int contextStart = Math.max(0, occ.startLine() - 3);
      int contextEnd = Math.min(totalOriginalLines - 1, occ.endLine() + 3);

      if (lastPrintedOriginalLine == -1) {
        if (contextStart > 0) {
          sb.append("...\n");
        }
      } else if (contextStart > lastPrintedOriginalLine + 1) {
        sb.append("...\n");
      } else {
        contextStart = lastPrintedOriginalLine + 1;
      }

      for (int lineIdx = contextStart; lineIdx < occ.startLine(); lineIdx++) {
        sb.append(
            String.format(" %" + width + "d|%s\n", lineIdx + 1, originalDoc.lines().get(lineIdx)));
      }

      for (int lineIdx = occ.startLine(); lineIdx <= occ.endLine(); lineIdx++) {
        sb.append(
            String.format("-%" + width + "d|%s\n", lineIdx + 1, originalDoc.lines().get(lineIdx)));
      }

      String[] newLines = normalizedNew.split("\n", -1);
      for (int n = 0; n < newLines.length; n++) {
        int lineNum = occ.startLine() + 1 + n;
        sb.append(String.format("+%" + width + "d|%s\n", lineNum, newLines[n]));
      }

      int nextOccStart =
          (i + 1 < sorted.size()) ? sorted.get(i + 1).startLine() : Integer.MAX_VALUE;
      int followEnd = Math.min(contextEnd, nextOccStart - 1);
      for (int lineIdx = occ.endLine() + 1; lineIdx <= followEnd; lineIdx++) {
        sb.append(
            String.format(" %" + width + "d|%s\n", lineIdx + 1, originalDoc.lines().get(lineIdx)));
      }
      lastPrintedOriginalLine = followEnd;
    }

    if (lastPrintedOriginalLine < totalOriginalLines - 1) {
      sb.append("...\n");
    }

    String diff = sb.toString();
    if (diff.length() > 30 * 1024) {
      diff = diff.substring(0, 30 * 1024) + "\n... (diff truncated to fit inline limit)\n";
    }
    return diff.stripTrailing();
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
