package fun.fengwk.kkstudio.harness.common.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 有界字节输出流：累计写入超过 {@code maxBytes} 即抛出 {@link LimitExceededException} 中止，不物化完整内容。
 *
 * <p>用于在序列化边界按 UTF-8 输出字节数精确计量。两种模式共享同一上限判定：
 *
 * <ul>
 *   <li>{@code retainBytes = false}（默认）：只计数，不保留任何已写入字节，供 {@code fits} / {@code exceeds*} 之类的判定使用；
 *   <li>{@code retainBytes = true}：同时保留已写入字节，正常完成时可用 {@link #toUtf8String()} 还原文本。
 * </ul>
 *
 * <p>超限是原子中止：抛出异常时不写入本次调用的任何字节，也不推进已计数。
 */
public final class BoundedOutputStream extends OutputStream {

  private final int maxBytes;
  private final ByteArrayOutputStream retained;
  private int count;

  /** 创建只计数、不保留字节的有界输出流。 */
  public BoundedOutputStream(int maxBytes) {
    this(maxBytes, false);
  }

  /**
   * 创建有界输出流。
   *
   * @param maxBytes 正数字节上限
   * @param retainBytes 是否保留已写入字节
   * @throws IllegalArgumentException maxBytes 非正数时
   */
  public BoundedOutputStream(int maxBytes, boolean retainBytes) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    this.maxBytes = maxBytes;
    this.retained = retainBytes ? new ByteArrayOutputStream() : null;
  }

  @Override
  public void write(int b) throws IOException {
    check(1);
    if (retained != null) {
      retained.write(b);
    }
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    check(length);
    if (retained != null) {
      retained.write(buffer, offset, length);
    }
  }

  /**
   * 按 UTF-8 还原已保留的字节。
   *
   * @throws IllegalStateException 未启用 {@code retainBytes} 时
   */
  public String toUtf8String() {
    if (retained == null) {
      throw new IllegalStateException("serialized bytes were not retained");
    }
    return retained.toString(StandardCharsets.UTF_8);
  }

  private void check(int length) throws IOException {
    // count 恒 <= maxBytes 且 length >= 0（字节数组索引已校验），提升为 long 后比较避免 int 溢出。
    if ((long) count + length > maxBytes) {
      throw new LimitExceededException(maxBytes);
    }
    count += length;
  }

  /**
   * 累计写入超过 {@code maxBytes} 时抛出的专属 {@link IOException}。
   *
   * <p>刻意继承 {@link IOException}：Jackson 的生成器会把写入期异常原样上抛而不做二次包装，调用方可以直接按类型识别「超限中止」 而非「编码失败」。
   */
  public static final class LimitExceededException extends IOException {

    private LimitExceededException(int maxBytes) {
      super("bounded output exceeded " + maxBytes + " bytes");
    }
  }
}
