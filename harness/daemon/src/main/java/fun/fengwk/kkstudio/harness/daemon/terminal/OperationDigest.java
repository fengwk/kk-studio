package fun.fengwk.kkstudio.harness.daemon.terminal;

import java.util.Arrays;

/**
 * 一次 INPUT/RESIZE 操作的固定长度 SHA-256 摘要，是唯一保留的去重证据。
 *
 * <p>准入只持有该摘要，不保存原始输入字节；摘要用于重送确认、digest 冲突判定以及跨连接恢复时核对旧操作是否已执行。它不携带原始输入， {@link #toString()}
 * 也不回显任何字节，因此可以安全进入诊断输出。
 */
public final class OperationDigest {

  /** SHA-256 摘要的字节长度。 */
  public static final int LENGTH = 32;

  private final byte[] value;

  private OperationDigest(byte[] value) {
    this.value = value;
  }

  /**
   * 以恰好 {@value #LENGTH} 字节的摘要构造一次操作的摘要身份。
   *
   * @param value 原始摘要字节，会被防御性复制
   * @throws IllegalArgumentException 长度不为 {@value #LENGTH}
   */
  public static OperationDigest of(byte[] value) {
    if (value == null || value.length != LENGTH) {
      throw new IllegalArgumentException("operation digest must be exactly " + LENGTH + " bytes");
    }
    return new OperationDigest(value.clone());
  }

  /** 返回摘要字节的防御性副本。 */
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof OperationDigest that && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "OperationDigest[sha256]";
  }
}
