package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 大文本的共享流式读取边界：前缀探测、严格解码与逐行扫描。
 *
 * <p>read 与 grep 都需要在不受文件大小限制的前提下读取 Daemon 自己生成或外部工具产生的超大文本。这里统一“只读前缀做编码/二进制判定”“按固定大小缓冲流式解码”“遇到非法
 * UTF-8 或 NUL 以明确错误失败”的语义，避免两处各自实现出不一致的判定。
 *
 * <p>本类型不分配整文件内存：所有操作都在固定大小的缓冲上进行，因此读取成本与文件大小无关。
 */
final class TextStreams {

  /** 编码与二进制探测读取的前缀字节数：足够覆盖 BOM 与典型的二进制起始特征。 */
  static final int PROBE_BYTES = 8192;

  /** 流式逐行读取的单行最大字符数：超长行被截断而不是整体驻留内存。 */
  static final int MAX_LINE_CHARS = 64 * 1024;

  private TextStreams() {}

  /** 读取文件前缀；文件短于前缀时返回其全部内容。 */
  static byte[] probe(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return input.readNBytes(PROBE_BYTES);
    }
  }

  /** 按前缀 BOM 与二进制特征判定编码。 */
  static Encoding detectEncoding(byte[] probe) {
    return Encoding.detect(probe);
  }

  /**
   * 以严格解码逐行扫描文件；每行（不含行分隔符）交给 {@code consumer}，行号从 1 开始。
   *
   * <p>{@code consumer} 返回 false 表示提前终止扫描；返回的 {@link Outcome} 仍准确报告扫描到该点为止的总行数与是否以换行结尾。
   *
   * @return 扫描结果：总行数与“文件是否以换行符结尾”
   * @throws IllegalArgumentException 文件包含非法 UTF-8 序列或 NUL 字符
   */
  static Outcome forEachLine(Path path, Encoding encoding, LineConsumer consumer)
      throws IOException, InterruptedException {
    Objects.requireNonNull(encoding, "encoding");
    try (BufferedReader reader = new BufferedReader(encoding.openReader(path), MAX_LINE_CHARS)) {
      StringBuilder line = new StringBuilder();
      int lineNumber = 0;
      boolean truncated = false;
      char[] buffer = new char[8 * 1024];
      int count;
      boolean sawCr = false;
      boolean endedWithNewline = false;
      while ((count = reader.read(buffer)) >= 0) {
        for (int index = 0; index < count; index++) {
          char value = buffer[index];
          if (value == '\u0000') {
            throw new IllegalArgumentException("file appears to be binary: contains NUL character");
          }
          if (sawCr) {
            sawCr = false;
            lineNumber++;
            endedWithNewline = true;
            if (!consumer.accept(lineNumber, line.toString(), truncated)) {
              return new Outcome(lineNumber, true);
            }
            line.setLength(0);
            truncated = false;
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
            lineNumber++;
            endedWithNewline = true;
            if (!consumer.accept(lineNumber, line.toString(), truncated)) {
              return new Outcome(lineNumber, true);
            }
            line.setLength(0);
            truncated = false;
            continue;
          }
          endedWithNewline = false;
          if (line.length() < MAX_LINE_CHARS) {
            line.append(value);
          } else {
            truncated = true;
          }
        }
      }
      if (sawCr) {
        lineNumber++;
        consumer.accept(lineNumber, line.toString(), truncated);
        endedWithNewline = true;
      } else if (line.length() > 0 || truncated) {
        lineNumber++;
        consumer.accept(lineNumber, line.toString(), truncated);
        endedWithNewline = false;
      }
      return new Outcome(lineNumber, endedWithNewline && lineNumber > 0);
    }
  }

  /** 扫描结果：文件总行数与是否以换行符结尾。 */
  record Outcome(int totalLines, boolean endsWithNewline) {}

  /** 逐行消费者的返回值表示是否继续扫描。 */
  @FunctionalInterface
  interface LineConsumer {
    boolean accept(int lineNumber, String line, boolean truncated) throws InterruptedException;
  }

  /**
   * 文本编码：BOM 长度与 charset，另含基于前缀的二进制判定。
   *
   * <p>判定不读取整文件；真正的严格解码发生在流式读取过程中。
   */
  record Encoding(Charset charset, int bomLength, boolean utf16) {

    private static Encoding detect(byte[] probe) {
      if (probe.length >= 3
          && (probe[0] & 0xFF) == 0xEF
          && (probe[1] & 0xFF) == 0xBB
          && (probe[2] & 0xFF) == 0xBF) {
        return new Encoding(StandardCharsets.UTF_8, 3, false);
      }
      if (probe.length >= 2 && (probe[0] & 0xFF) == 0xFF && (probe[1] & 0xFF) == 0xFE) {
        return new Encoding(StandardCharsets.UTF_16LE, 2, true);
      }
      if (probe.length >= 2 && (probe[0] & 0xFF) == 0xFE && (probe[1] & 0xFF) == 0xFF) {
        return new Encoding(StandardCharsets.UTF_16BE, 2, true);
      }
      return new Encoding(StandardCharsets.UTF_8, 0, false);
    }

    /** 控制字符（NUL、除制表/换行/回车/ESC 外的控制码）视为二进制内容。 */
    boolean looksBinary(byte[] probe) {
      if (utf16) {
        return false;
      }
      for (byte value : probe) {
        int unsigned = value & 0xFF;
        if (unsigned == 0
            || unsigned < 0x09
            || (unsigned > 0x0D && unsigned < 0x20 && unsigned != 0x1B)) {
          return true;
        }
      }
      return false;
    }

    /** 打开严格解码的字符流；非法序列以 {@link IllegalArgumentException} 暴露而非静默替换。 */
    Reader openReader(Path path) throws IOException {
      InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
      if (bomLength > 0) {
        long skipped = input.skip(bomLength);
        if (skipped != bomLength) {
          input.close();
          throw new IOException("cannot skip byte order mark: " + path);
        }
      }
      CharsetDecoder decoder =
          charset
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return new InputStreamReader(input, decoder) {
        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
          try {
            return super.read(buffer, offset, length);
          } catch (IOException error) {
            if (error.getCause() instanceof CharacterCodingException) {
              throw new IllegalArgumentException(
                  "file appears to be binary: invalid text encoding", error);
            }
            throw error;
          }
        }
      };
    }
  }
}
