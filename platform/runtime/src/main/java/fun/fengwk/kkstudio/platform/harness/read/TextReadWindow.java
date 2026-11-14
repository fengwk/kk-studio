package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文本读取窗口核心：把已解码字符流单遍投影为有界窗口，并渲染标记文本之外的 header 与正文。
 *
 * <p>本地 {@code fs.read} 与受管文本读取共用这一份状态机。契约要点：
 *
 * <ul>
 *   <li>{@code offset}（默认 1）与 {@code column_offset}（默认 1）都从 1 开始；{@code limit} 默认且最大 {@link
 *       #MAX_LIMIT} 行；{@code column_offset} 只作用于起始行片段，不限制 {@code limit}。
 *   <li>正文累计最多 {@link #MAX_BODY_CODE_POINTS} 个 Unicode 码点，不计行号、元数据与行分隔符；起始行片段占一行额度，后续行从第 1 列读取。
 *   <li>LF、CRLF 与孤立 CR 都是行边界；正文不截断单行长度，不拆开代理对，也不丢弃行尾。
 *   <li>起点超过 EOF（含空文件）输出 {@code range: empty}；有效目标行上越界列报错；无字符空行的合法端点是第 1 列。
 *   <li>截断时 header 追加 {@code truncated}/{@code truncation_reason}/{@code
 *       next}，正文后只输出一行仅含位置的尾部警告；未截断时全部省略。
 *   <li>必须扫描到 EOF 才能准确报告总行数与文件级 {@code ends_with_newline}，但只保留窗口内正文，内存与流长度无关。
 *   <li>行号与列号内部用 {@code long} 计数；超出参数协议 {@code int} 范围的位置显式拒绝，不伪造越界元数据。
 * </ul>
 *
 * <p>{@link #read} 使用调用方已解码、校验的字符流，不自行剥离 BOM 或关闭流。{@code format} 对受管文本执行严格 UTF-8 解码、首字符 BOM
 * 剥离和参数校验；调用方负责关闭输入流。IO 映射为 {@link PlatformReadException}，存储超时等未检查异常原样传播。
 *
 * <p>非文本判定与 Daemon codec 一致：前 {@value #BINARY_PROBE_BYTES} 字节内出现 NUL 字节是二进制文件；其余无法按 UTF-8 解码的内容
 * （含畸形代理对）明确报告不是 UTF-8 文本，不误报为二进制；{@value #BINARY_PROBE_BYTES} 字节之后的 NUL 按文本处理。
 */
public final class TextReadWindow {

  /** 缺省返回行数上限。 */
  public static final int DEFAULT_LIMIT = 2000;

  /** 最大返回行数上限。 */
  public static final int MAX_LIMIT = 2000;

  /** 整个窗口保留的正文码点上限。 */
  public static final int MAX_BODY_CODE_POINTS = 60000;

  /** 流式读取的字符缓冲区：决定驻留内存上界，与流长度无关。 */
  private static final int READ_BUFFER_CHARS = 8 * 1024;

  /** 二进制探测窗口：只在前 8000 字节内把 NUL 当二进制证据，与 Daemon codec 一致。 */
  private static final int BINARY_PROBE_BYTES = 8000;

  private TextReadWindow() {}

  /** 按默认 LSP 状态（{@code unsupported}）格式化原始 UTF-8 字节。 */
  public static String format(
      byte[] bytes,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      ReadDeadline deadline) {
    return format(bytes, offset, limit, columnOffset, displayPath, "unsupported", deadline);
  }

  /** 格式化原始 UTF-8 字节；{@code bytes} 为空数组时表示空资源。 */
  public static String format(
      byte[] bytes,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus,
      ReadDeadline deadline) {
    Objects.requireNonNull(bytes, "bytes");
    return format(
        new ByteArrayInputStream(bytes),
        offset,
        limit,
        columnOffset,
        displayPath,
        lspStatus,
        deadline);
  }

  /** 消费但不关闭输入流；调用方负责在解析成功或失败后关闭资源。 */
  public static String format(
      InputStream stream,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus,
      ReadDeadline deadline) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(displayPath, "displayPath");
    Objects.requireNonNull(lspStatus, "lspStatus");
    Objects.requireNonNull(deadline, "deadline");

    if (offset != null && offset < 1) {
      throw PlatformReadException.invalidArgument("offset must be a positive integer");
    }
    if (columnOffset != null && columnOffset < 1) {
      throw PlatformReadException.invalidArgument("column_offset must be a positive integer");
    }
    int resolvedOffset = offset == null ? 1 : offset;
    int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
    if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
      throw PlatformReadException.invalidArgument(
          "limit must be a positive integer <= " + MAX_LIMIT);
    }

    Runnable checkpoint =
        () -> {
          if (Thread.currentThread().isInterrupted() || deadline.isExpired()) {
            throw PlatformReadException.timeout(
                "resource read interrupted or timed out: " + displayPath);
          }
        };
    try {
      return read(
          withoutLeadingBom(strictUtf8Reader(stream)),
          resolvedOffset,
          resolvedLimit,
          columnOffset,
          displayPath,
          lspStatus,
          checkpoint);
    } catch (CharacterCodingException error) {
      throw notUtf8(displayPath, error);
    } catch (IOException error) {
      throw new PlatformReadException("file cannot be read: " + displayPath, error);
    }
  }

  /** 二进制文件：只声明 NUL 证据与 read 的可用范围。 */
  private static PlatformReadException binary(String displayPath) {
    return PlatformReadException.notText(
        displayPath
            + " is a binary file (it contains NUL bytes); read supports text files, directories"
            + " and supported media.");
  }

  /** 不是合法 UTF-8：受管资源不声明编码，因此只能读 UTF-8。 */
  private static PlatformReadException notUtf8(String displayPath, Throwable cause) {
    return PlatformReadException.notText(
        displayPath
            + " is not UTF-8 text, so it was not read. Managed resources are read as UTF-8; to read"
            + " another encoding, convert it in an Environment, for example with bash iconv.",
        cause);
  }

  private static Reader strictUtf8Reader(InputStream stream) {
    return new InputStreamReader(
        stream,
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
  }

  /**
   * 在已解码字符流上投影一个窗口。
   *
   * @param reader 已严格解码的字符流；调用方负责按需剥离首个 BOM 字符并关闭它
   * @param offset 已校验的 1-based 起始行
   * @param limit 已校验的最大返回行数
   * @param columnOffset 已校验的起始行内 1-based 码点偏移，{@code null} 表示第 1 列
   * @param displayPath header 展示的路径
   * @param lspStatus LSP 状态；{@code null} 或 {@code "unsupported"} 时省略该 header 行
   * @param checkpoint 每块读取前后执行的检查点；抛出异常即中止扫描，用于超时与中断映射
   * @throws PlatformReadException 内容含二进制证据、不是 UTF-8 文本、目标行越界列或位置超出可分页范围
   * @throws IOException 读取失败
   */
  public static String read(
      Reader reader,
      int offset,
      int limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus,
      Runnable checkpoint)
      throws IOException {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(displayPath, "displayPath");
    Objects.requireNonNull(checkpoint, "checkpoint");
    Scanner scanner = new Scanner(offset, limit, columnOffset == null ? 1 : columnOffset);
    return format(scan(reader, scanner, displayPath, checkpoint), displayPath, lspStatus);
  }

  /**
   * 只剥离首个字符的 BOM 包装：仅当流的第 1 个字符是 {@code \uFEFF} 时跳过它。
   *
   * <p>核心不默认剥离 BOM：本地读取已在字节层剥掉 BOM，若此处再剥就会吞掉正文里真正的第二个 {@code \uFEFF}。
   */
  public static Reader withoutLeadingBom(Reader reader) {
    Objects.requireNonNull(reader, "reader");
    return new Reader() {

      private boolean leading = true;

      @Override
      public int read(char[] buffer, int offset, int length) throws IOException {
        int count = reader.read(buffer, offset, length);
        if (!leading || count <= 0) {
          return count;
        }
        leading = false;
        if (buffer[offset] != '\uFEFF') {
          return count;
        }
        if (count > 1) {
          System.arraycopy(buffer, offset + 1, buffer, offset, count - 1);
          return count - 1;
        }
        return reader.read(buffer, offset, length);
      }

      @Override
      public void close() throws IOException {
        reader.close();
      }
    };
  }

  /** 协议位置是 {@code int}：超出即显式拒绝，不伪造越界元数据。 */
  static int requireProtocolPosition(long value, String field) {
    if (value > Integer.MAX_VALUE) {
      throw PlatformReadException.invalidArgument(
          "text read position exceeds the supported integer range: " + field + "=" + value);
    }
    return (int) value;
  }

  private static Window scan(
      Reader reader, Scanner scanner, String displayPath, Runnable checkpoint) throws IOException {
    char[] buffer = new char[READ_BUFFER_CHARS];
    boolean skipLf = false;
    int pendingHigh = -1;
    // 已解码内容的 UTF-8 字节偏移：严格解码下每个码点的字节长度就是它的 UTF-8 长度， 因此可与 Daemon codec 一样按前 8000 字节判定二进制。
    long byteOffset = 0L;
    while (true) {
      checkpoint.run();
      int count;
      try {
        count = reader.read(buffer);
      } catch (CharacterCodingException error) {
        // 严格解码器抛出的编码异常（如非 UTF-8 的 8 位旧编码）不是二进制证据。
        throw notUtf8(displayPath, error);
      }
      if (count == -1) {
        break;
      }
      checkpoint.run();
      for (int index = 0; index < count; index++) {
        char value = buffer[index];
        if (pendingHigh >= 0) {
          if (!Character.isLowSurrogate(value)) {
            throw notUtf8(displayPath, null);
          }
          int codePoint = Character.toCodePoint((char) pendingHigh, value);
          pendingHigh = -1;
          skipLf = false;
          byteOffset = scanCodePoint(scanner, codePoint, byteOffset, displayPath);
          continue;
        }
        if (value == '\r') {
          scanner.onSeparator();
          skipLf = true;
          byteOffset++;
          continue;
        }
        if (value == '\n') {
          if (!skipLf) {
            scanner.onSeparator();
          }
          skipLf = false;
          byteOffset++;
          continue;
        }
        skipLf = false;
        if (Character.isHighSurrogate(value)) {
          if (index + 1 < count) {
            char low = buffer[index + 1];
            if (!Character.isLowSurrogate(low)) {
              throw notUtf8(displayPath, null);
            }
            index++;
            byteOffset =
                scanCodePoint(scanner, Character.toCodePoint(value, low), byteOffset, displayPath);
          } else {
            // 高代理项落在读取块末尾：留到下一块与低代理项合并，绝不以半个码点入正文。
            pendingHigh = value;
          }
          continue;
        }
        if (Character.isLowSurrogate(value)) {
          throw notUtf8(displayPath, null);
        }
        byteOffset = scanCodePoint(scanner, value, byteOffset, displayPath);
      }
    }
    if (pendingHigh >= 0) {
      throw notUtf8(displayPath, null);
    }
    return scanner.finish();
  }

  /**
   * 一个码点入正文：前 8000 字节内的 NUL 是二进制证据，之后的 NUL 按文本处理。
   *
   * @return 该码点之后的 UTF-8 字节偏移
   */
  private static long scanCodePoint(
      Scanner scanner, int codePoint, long byteOffset, String displayPath) {
    if (codePoint == 0 && byteOffset < BINARY_PROBE_BYTES) {
      throw binary(displayPath);
    }
    scanner.onCodePoint(codePoint);
    return byteOffset + utf8Length(codePoint);
  }

  /** 码点的 UTF-8 字节长度：严格解码后这就是它在源字节里的实际占用。 */
  private static int utf8Length(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }

  private static String format(Window window, String displayPath, String lspStatus) {
    List<String> output = new ArrayList<>();
    output.add("path: " + displayPath);
    output.add("ends_with_newline: " + (window.endsWithNewline() ? "yes" : "no"));
    output.add(
        window.emptyRange()
            ? "range: empty"
            : "range: "
                + window.startLine()
                + ":"
                + window.startColumn()
                + "-"
                + window.endLine()
                + ":"
                + window.endColumn());
    if (window.truncationReason() != null) {
      output.add("truncated: yes");
      output.add("truncation_reason: " + window.truncationReason());
      output.add("next: " + window.nextLine() + ":" + window.nextColumn());
    }
    if (lspStatus != null && !"unsupported".equals(lspStatus)) {
      output.add("lsp: " + lspStatus);
    }
    if (!window.body().isEmpty()) {
      output.add("");
      for (BodyLine line : window.body()) {
        output.add(String.format("%" + window.width() + "d|%s", line.number(), line.content()));
      }
    }
    if (window.truncationReason() != null) {
      if (!window.body().isEmpty()) {
        output.add("");
      }
      output.add(
          "[TRUNCATED: More file content remains. Next position: line "
              + window.nextLine()
              + ", column "
              + window.nextColumn()
              + ".]");
    }
    return String.join("\n", output);
  }

  /** 一行编号正文。 */
  private record BodyLine(int number, String content) {}

  /** 投影结果：窗口元数据、截断结论与编号正文。 */
  private record Window(
      boolean endsWithNewline,
      boolean emptyRange,
      int startLine,
      int startColumn,
      int endLine,
      int endColumn,
      String truncationReason,
      int nextLine,
      int nextColumn,
      int width,
      List<BodyLine> body) {

    static Window empty(boolean endsWithNewline) {
      return new Window(endsWithNewline, true, 0, 0, 0, 0, null, 0, 0, 1, List.of());
    }
  }

  /**
   * 单遍扫描状态机：把已解码字符流折叠为窗口元数据与有界正文。
   *
   * <p>整个流都要经过这里，才能给出准确的总行数、流级 {@code ends_with_newline} 与“是否还有未返回内容”；但只有窗口内的起始行片段与后续行
   * 会被保留，因此内存与流长度无关。
   */
  private static final class Scanner {

    private final int offset;
    private final int limit;
    private final int columnStart;
    private final List<BodyLine> body = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private long line = 1;
    private long columns;
    private int capturedInLine;
    private int capturedCodePoints;
    private boolean budgetExhausted;
    private boolean lastLineCut;
    private boolean endedWithNewline;

    private Scanner(int offset, int limit, int columnStart) {
      this.offset = offset;
      this.limit = limit;
      this.columnStart = columnStart;
    }

    /** 一个文件内容码点：计数并在窗口内时追加到当前行；二进制判定在扫描层按字节窗口完成。 */
    private void onCodePoint(int codePoint) {
      columns++;
      endedWithNewline = false;
      if (line < offset || body.size() >= limit) {
        return;
      }
      if (line == offset && columns < columnStart) {
        return;
      }
      if (budgetExhausted) {
        return;
      }
      current.appendCodePoint(codePoint);
      capturedInLine++;
      capturedCodePoints++;
      if (capturedCodePoints >= MAX_BODY_CODE_POINTS) {
        budgetExhausted = true;
      }
    }

    /** 一个行分隔符（CRLF 已折叠为一个）：结束当前行并推进行号。 */
    private void onSeparator() {
      finishLine();
      line++;
      columns = 0;
      endedWithNewline = true;
    }

    private void finishLine() {
      if (line == offset && columnStart > Math.max(columns, 1)) {
        throw PlatformReadException.invalidArgument(
            "column_offset "
                + columnStart
                + " is out of range: line "
                + line
                + " has "
                + columns
                + " columns");
      }
      if (line >= offset && body.size() < limit && (!budgetExhausted || capturedInLine > 0)) {
        long startColumn = line == offset ? columnStart : 1;
        body.add(new BodyLine(requireProtocolPosition(line, "line"), current.toString()));
        lastLineCut = capturedInLine < Math.max(0, columns - startColumn + 1);
      }
      current.setLength(0);
      capturedInLine = 0;
    }

    /** 扫描到 EOF：收尾最后一行并得出窗口结论。 */
    private Window finish() {
      if (columns > 0) {
        finishLine();
      }
      long totalLines = columns > 0 ? line : line - 1;
      if (offset > totalLines) {
        return Window.empty(endedWithNewline);
      }
      BodyLine last = body.get(body.size() - 1);
      long lastStartColumn = last.number() == offset ? columnStart : 1;
      long endColumn =
          Math.max(
              1, lastStartColumn - 1 + last.content().codePointCount(0, last.content().length()));
      boolean truncated = lastLineCut || totalLines > last.number();
      String reason = null;
      long nextLine = 0;
      long nextColumn = 0;
      if (truncated) {
        reason = budgetExhausted ? "character_limit" : "line_limit";
        if (lastLineCut) {
          nextLine = last.number();
          nextColumn = endColumn + 1;
        } else {
          nextLine = last.number() + 1L;
          nextColumn = 1;
        }
      }
      return new Window(
          endedWithNewline,
          false,
          offset,
          columnStart,
          last.number(),
          requireProtocolPosition(endColumn, "column"),
          reason,
          requireProtocolPosition(nextLine, "line"),
          requireProtocolPosition(nextColumn, "column"),
          Math.max(1, Long.toString(Math.max(1, totalLines)).length()),
          List.copyOf(body));
    }
  }
}
