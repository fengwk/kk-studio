package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** 读取有界文本窗口或确定性的目录清单。 */
public final class ReadCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 2000;
  private static final int MAX_LINE_CODE_POINTS = 2000;
  private static final long MAX_FILE_BYTES = 64 * 1024 * 1024L;
  private static final int MAX_RESPONSE_BYTES = 48 * 1024;

  public ReadCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);
    Integer columnOffset = parseOptionalPositiveInt(args, "column_offset");

    if (Files.isDirectory(path)) {
      if (columnOffset != null) {
        throw new IllegalArgumentException("column_offset is only supported for text files");
      }
      List<String> names;
      try (var entries = Files.list(path)) {
        names =
            entries
                .map(ReadCapability::directoryEntryName)
                .sorted(Comparator.naturalOrder())
                .toList();
      }
      int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
      int limit = optionalPositiveInt(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);
      int totalEntries = names.size();
      int start = Math.min(offset, totalEntries + 1);
      int end = Math.min(totalEntries, start + limit - 1);

      List<String> headers = List.of("path: " + displayPath, "kind: directory", "");
      if (offset > totalEntries) {
        List<String> output = new ArrayList<>(headers);
        output.add("[Showing 0 entries of " + totalEntries + ".]");
        return textResponse(request.call().id(), String.join("\n", output));
      }

      int actualEnd = start - 1;
      List<String> entriesList = new ArrayList<>();
      if (start <= totalEntries) {
        for (int i = start; i <= end; i++) {
          String entry = names.get(i - 1);
          List<String> candidateEntries = new ArrayList<>(entriesList);
          candidateEntries.add(entry);

          List<String> candidateOutput = new ArrayList<>(headers);
          candidateOutput.addAll(candidateEntries);
          if (i < totalEntries) {
            candidateOutput.add("");
            candidateOutput.add(
                "[Showing entries "
                    + start
                    + "-"
                    + i
                    + " of "
                    + totalEntries
                    + ". Re-run read with offset="
                    + (i + 1)
                    + " to continue.]");
          }
          int candidateBytes = responseUtf8Bytes(candidateOutput);
          if (candidateBytes > MAX_RESPONSE_BYTES) {
            if (i == start) {
              throw new IllegalStateException(
                  "directory read response exceeds "
                      + MAX_RESPONSE_BYTES
                      + " bytes on first entry: "
                      + candidateBytes
                      + " bytes");
            }
            break;
          }
          entriesList = candidateEntries;
          actualEnd = i;
        }
      }

      List<String> output = new ArrayList<>(headers);
      output.addAll(entriesList);
      if (actualEnd < totalEntries && start <= totalEntries) {
        output.add("");
        output.add(
            "[Showing entries "
                + start
                + "-"
                + actualEnd
                + " of "
                + totalEntries
                + ". Re-run read with offset="
                + (actualEnd + 1)
                + " to continue.]");
      }

      return textResponse(request.call().id(), String.join("\n", output));
    }

    long fileSize = Files.size(path);
    if (fileSize > MAX_FILE_BYTES) {
      throw new IllegalArgumentException(
          "file exceeds 64 MiB maximum read limit: " + fileSize + " bytes");
    }
    byte[] bytes = Files.readAllBytes(path);

    String imageMime = detectImageMediaType(bytes);
    if (imageMime != null) {
      if (columnOffset != null) {
        throw new IllegalArgumentException("column_offset is only supported for text files");
      }
      DaemonResourceRef stored = config.resourceStore().store(bytes, imageMime);
      ResourceRef ref =
          new ResourceRef(
              stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
      return new EnvironmentCapabilityResult(
          request.call().id(), List.of(new ResourceResultContent(ref)), false, "{}");
    }

    TextFileCodec.Decoded decoded = TextFileCodec.decode(bytes);
    int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
    int defaultLimit = columnOffset != null ? 1 : DEFAULT_LIMIT;
    int limit = optionalPositiveInt(args, "limit", defaultLimit, MAX_LIMIT);
    if (columnOffset != null && limit != 1) {
      throw new IllegalArgumentException("limit must be 1 when column_offset is specified");
    }
    String original = decoded.text();
    boolean endsWithNewline = original.endsWith("\n") || original.endsWith("\r");
    String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines =
        original.isEmpty() ? List.of() : new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (endsWithNewline && !lines.isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    int totalLines = lines.size();
    int start = Math.min(offset, totalLines + 1);
    int end = Math.min(totalLines, start + limit - 1);

    String lang = detectLanguage(path);
    String lspStatus =
        config.lspBridgeCommand() != null
            ? ("supported" + (lang != null ? " (" + lang + ")" : ""))
            : "unsupported";

    List<String> headers = new ArrayList<>();
    headers.add("path: " + displayPath);
    headers.add("ends_with_newline: " + (endsWithNewline ? "yes" : "no"));
    headers.add("lsp: " + lspStatus);
    headers.add("");

    if (offset > totalLines) {
      List<String> output = new ArrayList<>(headers);
      output.add("[Showing 0 lines of " + totalLines + ".]");
      return textResponse(request.call().id(), String.join("\n", output));
    }

    int width = Math.max(1, Integer.toString(Math.max(1, totalLines)).length());
    int actualEnd = start - 1;
    List<String> bodyLines = new ArrayList<>();
    List<String> columnFooters = new ArrayList<>();

    if (start <= totalLines) {
      for (int index = start; index <= end; index++) {
        String line = lines.get(index - 1);
        LineSlice slice = sliceLine(line, index, width, columnOffset);

        List<String> candidateBody = new ArrayList<>(bodyLines);
        if (slice.formatted() != null) {
          candidateBody.add(slice.formatted());
        }
        List<String> candidateColumnFooters = new ArrayList<>(columnFooters);
        if (slice.columnFooter() != null) {
          candidateColumnFooters.add(slice.columnFooter());
        }
        List<String> candidateFooters =
            buildFooters(start, index, totalLines, columnOffset, candidateColumnFooters);
        List<String> candidateOutput = assembleOutput(headers, candidateBody, candidateFooters);
        int candidateBytes = responseUtf8Bytes(candidateOutput);

        if (candidateBytes > MAX_RESPONSE_BYTES) {
          if (index == start) {
            throw new IllegalStateException(
                "read response exceeds "
                    + MAX_RESPONSE_BYTES
                    + " bytes on first line: "
                    + candidateBytes
                    + " bytes");
          }
          break;
        }

        bodyLines = candidateBody;
        columnFooters = candidateColumnFooters;
        actualEnd = index;
      }
    }

    List<String> footers = buildFooters(start, actualEnd, totalLines, columnOffset, columnFooters);
    List<String> output = assembleOutput(headers, bodyLines, footers);
    return textResponse(request.call().id(), String.join("\n", output));
  }

  static String detectImageMediaType(byte[] bytes) {
    if (bytes == null || bytes.length < 3) {
      return null;
    }
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G'
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 6
        && bytes[0] == 'G'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == '8'
        && (bytes[4] == '7' || bytes[4] == '9')
        && bytes[5] == 'a') {
      return "image/gif";
    }
    if (bytes.length >= 12
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  static String detectLanguage(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return null;
    }
    String name = fileName.toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".java")) {
      return "java";
    }
    if (name.endsWith(".ts")) {
      return "typescript";
    }
    if (name.endsWith(".js")) {
      return "javascript";
    }
    if (name.endsWith(".py")) {
      return "python";
    }
    if (name.endsWith(".go")) {
      return "go";
    }
    if (name.endsWith(".rs")) {
      return "rust";
    }
    if (name.endsWith(".c") || name.endsWith(".h")) {
      return "c";
    }
    if (name.endsWith(".cpp") || name.endsWith(".hpp") || name.endsWith(".cc")) {
      return "cpp";
    }
    return null;
  }

  private record LineSlice(String formatted, String columnFooter) {}

  private static LineSlice sliceLine(String line, int index, int width, Integer columnOffset) {
    int totalLineCodePoints = line.codePointCount(0, line.length());
    int colStart = columnOffset != null ? columnOffset : 1;
    if (colStart > totalLineCodePoints) {
      if (columnOffset != null) {
        return new LineSlice(
            null, "[Showing 0 columns of " + totalLineCodePoints + " on line " + index + ".]");
      }
      return new LineSlice(String.format("%" + width + "d|", index), null);
    }
    int colEnd = Math.min(totalLineCodePoints, colStart + MAX_LINE_CODE_POINTS - 1);
    int charStart = line.offsetByCodePoints(0, colStart - 1);
    int charEnd = line.offsetByCodePoints(0, colEnd);
    String fragment = line.substring(charStart, charEnd);
    String formatted = String.format("%" + width + "d|%s", index, fragment);
    String columnFooter;
    if (colEnd < totalLineCodePoints) {
      columnFooter =
          "[Showing columns "
              + colStart
              + "-"
              + colEnd
              + " of "
              + totalLineCodePoints
              + " on line "
              + index
              + ". Re-run read with offset="
              + index
              + ", limit=1, column_offset="
              + (colEnd + 1)
              + " to continue.]";
    } else if (columnOffset != null) {
      columnFooter =
          "[Showing columns "
              + colStart
              + "-"
              + colEnd
              + " of "
              + totalLineCodePoints
              + " on line "
              + index
              + ".]";
    } else {
      columnFooter = null;
    }
    return new LineSlice(formatted, columnFooter);
  }

  private static Integer parseOptionalPositiveInt(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isInt() || value.intValue() < 1) {
      throw new IllegalArgumentException(name + " must be a positive integer");
    }
    return value.intValue();
  }

  private static String directoryEntryName(Path entry) {
    Path fileName = entry.getFileName();
    String name = fileName == null ? entry.toString() : fileName.toString();
    return name + (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "/" : "");
  }

  static EnvironmentCapabilityResult textResponse(String callId, String text) {
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_RESPONSE_BYTES) {
      throw new IllegalStateException(
          "read response exceeds " + MAX_RESPONSE_BYTES + " bytes invariant: " + bytes + " bytes");
    }
    return EnvironmentCapabilityResult.text(callId, text);
  }

  private static int responseUtf8Bytes(List<String> lines) {
    if (lines.isEmpty()) {
      return 0;
    }
    int bytes = lines.size() - 1;
    for (String line : lines) {
      bytes += line.getBytes(StandardCharsets.UTF_8).length;
    }
    return bytes;
  }

  private static List<String> buildFooters(
      int start, int actualEnd, int totalLines, Integer columnOffset, List<String> columnFooters) {
    List<String> footers = new ArrayList<>();
    if (actualEnd < totalLines && start <= totalLines && columnOffset == null) {
      footers.add(
          "[Showing lines "
              + start
              + "-"
              + actualEnd
              + " of "
              + totalLines
              + ". Re-run read with offset="
              + (actualEnd + 1)
              + " to continue.]");
    }
    footers.addAll(columnFooters);
    return footers;
  }

  private static List<String> assembleOutput(
      List<String> headers, List<String> bodyLines, List<String> footers) {
    List<String> output = new ArrayList<>(headers);
    output.addAll(bodyLines);
    if (!footers.isEmpty()) {
      if (!bodyLines.isEmpty()) {
        output.add("");
      }
      output.addAll(footers);
    }
    return output;
  }
}
