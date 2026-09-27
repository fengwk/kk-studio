package fun.fengwk.kkstudio.harness.common.text;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文本读取窗口核心：把已解码字符流单遍投影为有界窗口，并渲染标记文本之外的 header 与正文。
 *
 * <p>本地 {@code fs.read} 与受管文本读取共用这一份状态机，两侧适配器只负责解码、BOM 与失败映射。契约要点：
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
 * <p>核心按调用方提供的字符流工作，不自行判定编码、不自行剥离 BOM、不关闭流：BOM 剥离必须由调用方严格只作用于首个字符，否则会吞掉正文里的 {@code
 * \uFEFF}；参数合法性也由调用方校验，核心只负责扫描与渲染。
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

  private static final String BINARY_MESSAGE = "file appears to be binary";

  private TextReadWindow() {}

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
   * @throws IllegalArgumentException 目标行越界列、内容看似二进制或位置超出可分页范围
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
    return format(scan(reader, scanner, checkpoint), displayPath, lspStatus);
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
      throw new IllegalArgumentException(
          "text read position exceeds the supported integer range: " + field + "=" + value);
    }
    return (int) value;
  }

  private static Window scan(Reader reader, Scanner scanner, Runnable checkpoint)
      throws IOException {
    char[] buffer = new char[READ_BUFFER_CHARS];
    boolean skipLf = false;
    int pendingHigh = -1;
    while (true) {
      checkpoint.run();
      int count;
      try {
        count = reader.read(buffer);
      } catch (CharacterCodingException error) {
        // 严格解码器直接抛出编码异常（如非 UTF-8 的 8 位旧编码），在读取契约上统一为“看似二进制”。
        throw new IllegalArgumentException(BINARY_MESSAGE + ": invalid text encoding", error);
      }
      if (count == -1) {
        break;
      }
      checkpoint.run();
      for (int index = 0; index < count; index++) {
        char value = buffer[index];
        if (pendingHigh >= 0) {
          if (!Character.isLowSurrogate(value)) {
            throw new IllegalArgumentException(BINARY_MESSAGE + ": malformed text encoding");
          }
          int codePoint = Character.toCodePoint((char) pendingHigh, value);
          pendingHigh = -1;
          skipLf = false;
          scanner.onCodePoint(codePoint);
          continue;
        }
        if (value == '\r') {
          scanner.onSeparator();
          skipLf = true;
          continue;
        }
        if (value == '\n') {
          if (!skipLf) {
            scanner.onSeparator();
          }
          skipLf = false;
          continue;
        }
        skipLf = false;
        if (Character.isHighSurrogate(value)) {
          if (index + 1 < count) {
            char low = buffer[index + 1];
            if (!Character.isLowSurrogate(low)) {
              throw new IllegalArgumentException(BINARY_MESSAGE + ": malformed text encoding");
            }
            index++;
            scanner.onCodePoint(Character.toCodePoint(value, low));
          } else {
            // 高代理项落在读取块末尾：留到下一块与低代理项合并，绝不以半个码点入正文。
            pendingHigh = value;
          }
          continue;
        }
        if (Character.isLowSurrogate(value)) {
          throw new IllegalArgumentException(BINARY_MESSAGE + ": malformed text encoding");
        }
        scanner.onCodePoint(value);
      }
    }
    if (pendingHigh >= 0) {
      throw new IllegalArgumentException(BINARY_MESSAGE + ": malformed text encoding");
    }
    return scanner.finish();
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

    /** 一个文件内容码点：计数、校验二进制特征，并在窗口内时追加到当前行。 */
    private void onCodePoint(int codePoint) {
      if (codePoint == 0) {
        throw new IllegalArgumentException(BINARY_MESSAGE + ": contains NUL character");
      }
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
        throw new IllegalArgumentException(
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
