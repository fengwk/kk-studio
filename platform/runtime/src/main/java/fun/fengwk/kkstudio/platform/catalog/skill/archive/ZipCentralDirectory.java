package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import java.io.IOException;

/**
 * ZIP 中央目录预检：在把字节交给 {@code ZipFile} 之前，按调用方上限校验目录结构与条目链。
 *
 * <p>{@code ZipFile} 会先物化中央目录再暴露条目；因此这里先自行计数，避免体积很小但空条目极多的归档在建目录时耗尽内存。
 *
 * <p>校验顺序是对外契约，调用方按异常消息区分拒绝原因：定位 EOCD → 单盘检查 →（磁盘号或计数溢出时）ZIP64 locator 与 record → 目录边界 →
 * 条目链与条目数。每一步失败立即抛出，不再继续后续检查。
 */
final class ZipCentralDirectory {

  private static final int END_OF_DIRECTORY_LENGTH = 22;
  private static final int MAX_COMMENT_LENGTH = 65535;
  private static final int CENTRAL_ENTRY_LENGTH = 46;
  private static final long END_OF_DIRECTORY_SIGNATURE = 0x06054b50L;
  private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
  private static final long ZIP64_END_SIGNATURE = 0x06064b50L;
  private static final long ZIP64_LOCATOR_LENGTH = 20L;
  private static final long ZIP64_END_MIN_LENGTH = 56L;
  private static final long CENTRAL_ENTRY_SIGNATURE = 0x02014b50L;
  private static final long MAX_UINT16 = 0xffffL;
  private static final long MAX_UINT32 = 0xffffffffL;

  private ZipCentralDirectory() {}

  /** 校验归档的中央目录；{@code maxEntries} 同时约束声明条目数与实际条目链长度。 */
  static void verify(byte[] bytes, int maxEntries) throws IOException {
    int end = locateEndOfDirectory(bytes);
    if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0) {
      throw new IOException("Invalid or multi-disk ZIP");
    }
    Directory directory = readDirectory(bytes, end);
    requireWithinBounds(directory, maxEntries);
    requireEntryChain(bytes, directory, maxEntries);
  }

  /** 从尾部向前扫描单条 EOCD 记录：注释长度必须刚好补齐到归档末尾，否则视为未找到。 */
  private static int locateEndOfDirectory(byte[] bytes) {
    int last = Math.max(0, bytes.length - END_OF_DIRECTORY_LENGTH - MAX_COMMENT_LENGTH);
    for (int p = bytes.length - END_OF_DIRECTORY_LENGTH; p >= last; p--) {
      if (u32(bytes, p) == END_OF_DIRECTORY_SIGNATURE
          && p + END_OF_DIRECTORY_LENGTH + u16(bytes, p + 20) == bytes.length) {
        return p;
      }
    }
    return -1;
  }

  /** 读取目录起止范围；ZIP64 标记存在时必须通过 locator 找到 64 位 record。 */
  private static Directory readDirectory(byte[] bytes, int end) throws IOException {
    long declared = u16(bytes, end + 10);
    long start = u32(bytes, end + 16);
    long size = u32(bytes, end + 12);
    boolean zip64 =
        (end >= ZIP64_LOCATOR_LENGTH && u32(bytes, end - 20) == ZIP64_LOCATOR_SIGNATURE)
            || declared == MAX_UINT16
            || start == MAX_UINT32
            || size == MAX_UINT32;
    if (zip64) {
      return readZip64Record(bytes, end);
    }
    if (declared != u16(bytes, end + 8)) {
      throw new IOException("Invalid ZIP directory count");
    }
    return new Directory(declared, start, size, end);
  }

  private static Directory readZip64Record(byte[] bytes, int end) throws IOException {
    int locator = end - 20;
    if (locator < 0
        || u32(bytes, locator) != ZIP64_LOCATOR_SIGNATURE
        || u32(bytes, locator + 4) != 0
        || u32(bytes, locator + 16) != 1) {
      throw new IOException("Invalid ZIP64 locator");
    }
    long record = u64(bytes, locator + 8);
    if (record < 0 || record > locator - ZIP64_END_MIN_LENGTH) {
      throw new IOException("Invalid ZIP64 offset");
    }
    int position = (int) record;
    long recordSize = u64(bytes, position + 4);
    if (u32(bytes, position) != ZIP64_END_SIGNATURE
        || recordSize < 44
        || recordSize != locator - record - 12
        || u32(bytes, position + 16) != 0
        || u32(bytes, position + 20) != 0
        || u64(bytes, position + 24) != u64(bytes, position + 32)) {
      throw new IOException("Invalid ZIP64 directory");
    }
    long declared = u64(bytes, position + 32);
    long size = u64(bytes, position + 40);
    long start = u64(bytes, position + 48);
    return new Directory(declared, start, size, record);
  }

  private static void requireWithinBounds(Directory directory, int maxEntries) throws IOException {
    if (directory.declared() < 0
        || directory.declared() > maxEntries
        || directory.size() < 0
        || directory.start() < 0
        || directory.start() > directory.end()
        || directory.size() != directory.end() - directory.start()) {
      throw new IOException("Invalid or oversized ZIP directory");
    }
  }

  /** 逐条校验中央目录条目；不与 EOCD 声明计数作单条比较，末尾统一核对总数。 */
  private static void requireEntryChain(byte[] bytes, Directory directory, int maxEntries)
      throws IOException {
    int position = (int) directory.start();
    int count = 0;
    while (position < directory.end()) {
      if (directory.end() - position < CENTRAL_ENTRY_LENGTH
          || u32(bytes, position) != CENTRAL_ENTRY_SIGNATURE) {
        throw new IOException("Invalid ZIP directory entry");
      }
      long next =
          position
              + CENTRAL_ENTRY_LENGTH
              + u16(bytes, position + 28)
              + u16(bytes, position + 30)
              + u16(bytes, position + 32);
      if (next > directory.end() || ++count > maxEntries) {
        throw new IOException("ZIP directory limit exceeded");
      }
      position = (int) next;
    }
    if (count != directory.declared()) {
      throw new IOException("Invalid ZIP directory count");
    }
  }

  private static int u16(byte[] bytes, int offset) {
    return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
  }

  private static long u32(byte[] bytes, int offset) {
    return (u16(bytes, offset) & MAX_UINT16) | (long) u16(bytes, offset + 2) << 16;
  }

  private static long u64(byte[] bytes, int offset) {
    return u32(bytes, offset) | u32(bytes, offset + 4) << 32;
  }

  private record Directory(long declared, long start, long size, long end) {}
}
