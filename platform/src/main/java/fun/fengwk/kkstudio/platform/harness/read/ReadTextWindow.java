package fun.fengwk.kkstudio.platform.harness.read;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将原始字节格式化为与 daemon {@code fs.read} 文本窗口语义完全一致的有界文本。
 *
 * <p>核心规格与约束：
 *
 * <ul>
 *   <li>输入字节数上限：8 MiB（{@link #MAX_SOURCE_BYTES} = 8,388,608 字节），超过直接抛出 {@link
 *       PlatformReadException}。
 *   <li>二进制判定：包含 NUL 字符（{@code \u0000}）或不是合法 UTF-8 编码时，抛出 {@link PlatformReadException}。
 *   <li>行分隔统一按 LF 处理（CRLF 与单独 CR 均记一次换行）；空文件总行数为 0；文件以换行结尾时 {@code ends_with_newline: yes}。
 *   <li>响应 UTF-8 字节上限为 48 KiB（{@link #MAX_RESPONSE_BYTES} = 49,152 字节）；超出时提前截断并输出分页
 *       footer；第一行即超限则直接失败。
 *   <li>单行字符上限为 2000 个 Unicode code point，超长行截断并输出列分页 footer。
 *   <li>{@code offset} 缺省为 1，必须为正整数；{@code limit} 缺省 200，上限 2000；指定 {@code column_offset} 时 {@code
 *       limit} 必须为 1。
 * </ul>
 */
public final class ReadTextWindow {

  /** 缺省返回行数限制。 */
  public static final int DEFAULT_LIMIT = 200;

  /** 最大返回行数限制。 */
  public static final int MAX_LIMIT = 2000;

  /** 单行最大展示的 Unicode code point 数量。 */
  public static final int MAX_LINE_CODE_POINTS = 2000;

  /** 整个格式化响应的 UTF-8 字节数严格上限（48 KiB）。 */
  public static final int MAX_RESPONSE_BYTES = 48 * 1024;

  /** 单次读取允许的最大源字节数（8 MiB）。 */
  public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;

  private ReadTextWindow() {}

  /**
   * 格式化原始文本字节为有界窗口文本，默认 lsp 状态为 "unsupported"。
   *
   * @param bytes 原始文件字节，非空
   * @param offset 起始行号，缺省为 1，从 1 开始计数
   * @param limit 最大返回行数，缺省为 200，上限为 2000
   * @param columnOffset 可选的行内起始 code point 偏移，从 1 开始计数
   * @param displayPath 用于在 header 中展示的路径或 URI，非空
   * @return 格式化后的有界文本结果
   * @throws PlatformReadException 参数不合法、内容超限、看似二进制或首行即超 48 KiB 时抛出
   */
  public static String format(
      byte[] bytes, Integer offset, Integer limit, Integer columnOffset, String displayPath) {
    return format(bytes, offset, limit, columnOffset, displayPath, "unsupported");
  }

  /**
   * 格式化原始文本字节为有界窗口文本。
   *
   * @param bytes 原始文件字节，非空
   * @param offset 起始行号，缺省为 1，从 1 开始计数
   * @param limit 最大返回行数，缺省为 200，上限为 2000
   * @param columnOffset 可选的行内起始 code point 偏移，从 1 开始计数
   * @param displayPath 用于在 header 中展示的路径或 URI，非空
   * @param lspStatus lsp 状态字符串，非空
   * @return 格式化后的有界文本结果
   * @throws PlatformReadException 参数不合法、内容超限、看似二进制或首行即超 48 KiB 时抛出
   */
  public static String format(
      byte[] bytes,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(displayPath, "displayPath");
    Objects.requireNonNull(lspStatus, "lspStatus");

    if (bytes.length > MAX_SOURCE_BYTES) {
      throw new PlatformReadException(
          "file exceeds maximum size limit of " + MAX_SOURCE_BYTES + " bytes: " + displayPath);
    }

    if (offset != null && offset < 1) {
      throw new PlatformReadException("offset must be a positive integer");
    }
    if (columnOffset != null && columnOffset < 1) {
      throw new PlatformReadException("column_offset must be a positive integer");
    }

    int resolvedOffset = offset != null ? offset : 1;
    int defaultLimit = columnOffset != null ? 1 : DEFAULT_LIMIT;
    int resolvedLimit = limit != null ? limit : defaultLimit;

    if (columnOffset != null && resolvedLimit != 1) {
      throw new PlatformReadException("limit must be 1 when column_offset is specified");
    }
    if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
      throw new PlatformReadException("limit must be between 1 and " + MAX_LIMIT);
    }

    DecodedText decodedText = decodeStrictUtf8(bytes, displayPath);
    List<String> lines = decodedText.lines();
    int totalLines = decodedText.totalLines();
    boolean endsWithNewline = decodedText.endsWithNewline();

    List<String> headers = new ArrayList<>();
    headers.add("path: " + displayPath);
    headers.add("ends_with_newline: " + (endsWithNewline ? "yes" : "no"));
    headers.add("lsp: " + lspStatus);
    headers.add("");

    if (resolvedOffset > totalLines) {
      List<String> output = new ArrayList<>(headers);
      output.add("[Showing 0 lines of " + totalLines + ".]");
      return String.join("\n", output);
    }

    int width = Math.max(1, Integer.toString(Math.max(1, totalLines)).length());
    int actualEnd = resolvedOffset - 1;
    int maxWanted = Math.min(totalLines, resolvedOffset + resolvedLimit - 1);
    List<String> bodyLines = new ArrayList<>();
    List<String> columnFooters = new ArrayList<>();

    for (int index = resolvedOffset; index <= maxWanted; index++) {
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
          buildFooters(resolvedOffset, index, totalLines, columnOffset, candidateColumnFooters);
      int candidateBytes =
          responseUtf8Bytes(assembleOutput(headers, candidateBody, candidateFooters));

      if (candidateBytes > MAX_RESPONSE_BYTES) {
        if (index == resolvedOffset) {
          throw new PlatformReadException(
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
        buildFooters(resolvedOffset, actualEnd, totalLines, columnOffset, columnFooters);
    return String.join("\n", assembleOutput(headers, bodyLines, footers));
  }

  private static DecodedText decodeStrictUtf8(byte[] bytes, String displayPath) {
    int bomLength = 0;
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF) {
      bomLength = 3;
    }

    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, bomLength, bytes.length - bomLength);
    CharBuffer charBuffer;
    try {
      charBuffer = decoder.decode(byteBuffer);
    } catch (CharacterCodingException e) {
      throw new PlatformReadException("file appears to be binary: " + displayPath, e);
    }

    for (int i = 0; i < charBuffer.length(); i++) {
      if (charBuffer.charAt(i) == '\u0000') {
        throw new PlatformReadException("file appears to be binary: " + displayPath);
      }
    }

    List<String> lines = new ArrayList<>();
    StringBuilder currentLine = new StringBuilder();
    boolean sawCr = false;
    boolean endedWithNewline = false;
    int totalLines = 0;

    for (int i = 0; i < charBuffer.length(); i++) {
      char value = charBuffer.charAt(i);
      if (sawCr) {
        sawCr = false;
        totalLines++;
        endedWithNewline = true;
        lines.add(currentLine.toString());
        currentLine.setLength(0);
        if (value == '\n') {
          continue;
        }
        endedWithNewline = false;
      }
      if (value == '\r') {
        sawCr = true;
        continue;
      }
      if (value == '\n') {
        totalLines++;
        endedWithNewline = true;
        lines.add(currentLine.toString());
        currentLine.setLength(0);
        continue;
      }
      endedWithNewline = false;
      currentLine.append(value);
    }

    if (sawCr) {
      totalLines++;
      lines.add(currentLine.toString());
      endedWithNewline = true;
    } else if (currentLine.length() > 0) {
      totalLines++;
      lines.add(currentLine.toString());
      endedWithNewline = false;
    }

    boolean finalEndsWithNewline = endedWithNewline && totalLines > 0;
    return new DecodedText(lines, totalLines, finalEndsWithNewline);
  }

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

  private record DecodedText(List<String> lines, int totalLines, boolean endsWithNewline) {}

  private record LineSlice(String formatted, String columnFooter) {}
}
