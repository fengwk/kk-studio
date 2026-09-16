package fun.fengwk.kkstudio.harness.daemon.coding;

/**
 * 固定容量的字节尾部缓冲：只保留最近写入的 {@code capacity} 字节。
 *
 * <p>实现为固定环形缓冲，追加与裁剪都是常数时间，用于大输出下保留有界诊断尾部而不随总体积增长内存。
 */
final class ByteTailBuffer {

  private final byte[] ring;
  private long written;

  ByteTailBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.ring = new byte[capacity];
  }

  /** 追加字节；超出容量时静默覆盖最旧的字节。 */
  void append(byte[] source, int offset, int length) {
    for (int index = 0; index < length; index++) {
      ring[(int) (written % ring.length)] = source[offset + index];
      written++;
    }
  }

  /** 当前保留的字节数，最多为容量。 */
  int size() {
    return (int) Math.min(written, ring.length);
  }

  /** 已保留尾部内容的副本。 */
  byte[] toByteArray() {
    int length = size();
    long first = written - length;
    byte[] result = new byte[length];
    for (int index = 0; index < length; index++) {
      result[index] = ring[(int) ((first + index) % ring.length)];
    }
    return result;
  }
}
