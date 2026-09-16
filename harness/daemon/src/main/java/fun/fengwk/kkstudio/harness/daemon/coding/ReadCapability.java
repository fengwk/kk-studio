package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * 读取有界文本窗口或确定性的目录清单。
 *
 * <p>文本读取不按整文件分配内存：媒体类型只由文件前缀判定；文本以固定大小的字符块流式解码，并按 offset/limit 只保留所需窗口内、且每行与整体都各有字符上界的行内容。因此
 * Daemon 自己或外部工具生成的超大文本（包括 {@code process.exec} 落盘的全文）都可以被分页读取，不存在“文本文件超过 N MiB 就拒绝”的限制。
 *
 * <p>图片仍是 Resource 语义：探测到受支持的图片签名时，整文件字节交给 ResourceStore 成为不可变附件。二进制判定同样来自流式解码：非法 UTF-8 序列或 NUL
 * 字符立即以明确的“看似二进制文件”失败。
 *
 * <p>输出协议保持稳定：同样的 header 行、同样的 {@code "N|"} 行格式、同样的截断与分页 footer、同样的 48 KiB 响应上界。
 */
public final class ReadCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 2000;
  private static final int MAX_LINE_CODE_POINTS = 2000;
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
      return directoryResponse(request, args, path, displayPath);
    }

    byte[] probe = TextStreams.probe(path);

    String imageMime = detectImageMediaType(probe);
    if (imageMime != null) {
      if (columnOffset != null) {
        throw new IllegalArgumentException("column_offset is only supported for text files");
      }
      byte[] bytes = Files.readAllBytes(path);
      DaemonResourceRef stored = config.resourceStore().store(bytes, imageMime);
      ResourceRef ref =
          new ResourceRef(
              stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
      return new EnvironmentCapabilityResult(
          request.call().id(), List.of(new ResourceResultContent(ref)), false, "{}");
    }

    TextStreams.Encoding encoding = TextStreams.detectEncoding(probe);
    if (encoding.looksBinary(probe)) {
      throw new IllegalArgumentException("file appears to be binary");
    }

    int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
    int defaultLimit = columnOffset != null ? 1 : DEFAULT_LIMIT;
    int limit = optionalPositiveInt(args, "limit", defaultLimit, MAX_LIMIT);
    if (columnOffset != null && limit != 1) {
      throw new IllegalArgumentException("limit must be 1 when column_offset is specified");
    }

    TextWindow window = TextWindow.scan(path, offset, limit, encoding);

    List<String> headers = new ArrayList<>();
    headers.add("path: " + displayPath);
    headers.add("ends_with_newline: " + (window.endsWithNewline() ? "yes" : "no"));
    headers.add("lsp: " + lspStatus(path));
    headers.add("");

    if (offset > window.totalLines()) {
      List<String> output = new ArrayList<>(headers);
      output.add("[Showing 0 lines of " + window.totalLines() + ".]");
      return textResponse(request.call().id(), String.join("\n", output));
    }

    int width = Math.max(1, Integer.toString(Math.max(1, window.totalLines())).length());
    int actualEnd = offset - 1;
    List<String> bodyLines = new ArrayList<>();
    List<String> columnFooters = new ArrayList<>();

    for (int index = offset; index <= window.totalLines(); index++) {
      String line = window.lineAt(index);
      if (line == null) {
        // 窗口字符预算已经耗尽：剩余部分由 footer 指引继续分页读取。
        break;
      }
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
          buildFooters(offset, index, window.totalLines(), columnOffset, candidateColumnFooters);
      int candidateBytes =
          responseUtf8Bytes(assembleOutput(headers, candidateBody, candidateFooters));

      if (candidateBytes > MAX_RESPONSE_BYTES) {
        if (index == offset) {
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

    List<String> footers =
        buildFooters(offset, actualEnd, window.totalLines(), columnOffset, columnFooters);
    return textResponse(
        request.call().id(), String.join("\n", assembleOutput(headers, bodyLines, footers)));
  }

  private String lspStatus(Path path) {
    if (config.lspBridgeCommand() == null) {
      return "unsupported";
    }
    String language = detectLanguage(path);
    return "supported" + (language != null ? " (" + language + ")" : "");
  }

  @SuppressWarnings("PMD.CognitiveComplexity")
  private EnvironmentCapabilityResult directoryResponse(
      EnvironmentCapabilityExecutionRequest request, JsonNode args, Path path, String displayPath)
      throws Exception {
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
      for (int index = start; index <= end; index++) {
        List<String> candidateEntries = new ArrayList<>(entriesList);
        candidateEntries.add(names.get(index - 1));

        List<String> candidateOutput = new ArrayList<>(headers);
        candidateOutput.addAll(candidateEntries);
        if (index < totalEntries) {
          candidateOutput.add("");
          candidateOutput.add(
              "[Showing entries "
                  + start
                  + "-"
                  + index
                  + " of "
                  + totalEntries
                  + ". Re-run read with offset="
                  + (index + 1)
                  + " to continue.]");
        }
        int candidateBytes = responseUtf8Bytes(candidateOutput);
        if (candidateBytes > MAX_RESPONSE_BYTES) {
          if (index == start) {
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
        actualEnd = index;
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

  /**
   * 流式文本窗口：单遍扫描文件，只保留请求区间内、字符总量有上界的行内容与确定性元数据。
   *
   * <p>行分隔统一为 LF：CRLF 与单独 CR 都记作一次换行。整个窗口的保留字符总量有上界，因此极大窗口不会带来无界内存；超出行内容上界的单行由 {@link
   * TextStreams#MAX_LINE_CHARS} 截断。窗口未覆盖的剩余部分由 footer 的分页指引覆盖。
   */
  private static final class TextWindow {

    /** 整个窗口保留字符上界：响应本身另有 48 KiB 上界，这里只作为病态输入的兜底。 */
    private static final int MAX_RETAINED_WINDOW_CHARS = 1024 * 1024;

    private final int totalLines;
    private final boolean endsWithNewline;
    private final int firstLineOffset;
    private final List<String> capturedLines;

    private TextWindow(
        int totalLines, boolean endsWithNewline, int firstLineOffset, List<String> capturedLines) {
      this.totalLines = totalLines;
      this.endsWithNewline = endsWithNewline;
      this.firstLineOffset = firstLineOffset;
      this.capturedLines = capturedLines;
    }

    /**
     * 扫描 {@code path} 并保留第 {@code [offset, offset + limit - 1]} 行。
     *
     * @throws IllegalArgumentException 文件不是合法文本或包含 NUL
     */
    private static TextWindow scan(Path path, int offset, int limit, TextStreams.Encoding encoding)
        throws IOException {
      int lastWanted = offset + limit - 1;
      List<String> captured = new ArrayList<>();
      int[] retainedChars = {0};
      TextStreams.Outcome outcome;
      try {
        outcome =
            TextStreams.forEachLine(
                path,
                encoding,
                (lineNumber, line, truncated) -> {
                  if (lineNumber >= offset
                      && lineNumber <= lastWanted
                      && retainedChars[0] < MAX_RETAINED_WINDOW_CHARS) {
                    captured.add(line);
                    retainedChars[0] += line.length();
                  }
                  return true;
                });
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("text scan was interrupted", error);
      }
      return new TextWindow(outcome.totalLines(), outcome.endsWithNewline(), offset, captured);
    }

    private int totalLines() {
      return totalLines;
    }

    private boolean endsWithNewline() {
      return endsWithNewline;
    }

    /** 按行号取已保留内容；不在保留范围内时返回 {@code null}。 */
    private String lineAt(int lineNumber) {
      int position = lineNumber - firstLineOffset;
      return position >= 0 && position < capturedLines.size() ? capturedLines.get(position) : null;
    }
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
