package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 生产者端有界输出捕获器。
 *
 * <p>在内联阈值（默认 &lt;= 50 KiB 且 &lt;= 2000 行）内使用有界内存缓冲； 一旦跨越阈值，立即转为私有 owner-only NOFOLLOW 临时文件流式写入；
 * 超过硬上限（默认 16 MiB）时停止捕获并标记 {@code OUTPUT_TOO_LARGE}。 发布完成后或发生取消/异常/关闭时，自动清理私有临时文件。
 */
public final class OutputSpool implements AutoCloseable {

  public static final int INLINE_MAX_BYTES = 50 * 1024;
  public static final int INLINE_MAX_LINES = 2000;
  public static final int PREVIEW_MAX_BYTES = 2 * 1024;
  public static final int PREVIEW_MAX_LINES = 20;
  public static final int HARD_CAP_BYTES = 16 * 1024 * 1024;

  private final int inlineMaxBytes;
  private final int inlineMaxLines;
  private final int hardCapBytes;
  private final ByteArrayOutputStream memoryBuffer;
  private final ByteArrayOutputStream previewBuffer;

  private long totalBytes = 0;
  private long lfCount = 0;
  private byte lastByte = 0;
  private boolean hasBytes = false;
  private boolean spilled = false;
  private boolean tooLarge = false;

  private Path tempFile;
  private FileChannel fileChannel;
  private OutputStream fileOutputStream;

  public OutputSpool() {
    this(INLINE_MAX_BYTES, INLINE_MAX_LINES, HARD_CAP_BYTES);
  }

  public OutputSpool(int inlineMaxBytes, int inlineMaxLines) {
    this(inlineMaxBytes, inlineMaxLines, HARD_CAP_BYTES);
  }

  public OutputSpool(int inlineMaxBytes, int inlineMaxLines, int hardCapBytes) {
    if (inlineMaxBytes <= 0 || inlineMaxLines <= 0 || hardCapBytes <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    this.inlineMaxBytes = inlineMaxBytes;
    this.inlineMaxLines = inlineMaxLines;
    this.hardCapBytes = hardCapBytes;
    this.memoryBuffer = new ByteArrayOutputStream(Math.min(inlineMaxBytes, 8192));
    this.previewBuffer = new ByteArrayOutputStream(PREVIEW_MAX_BYTES * 2);
  }

  /** 写入单个字节。 */
  public void write(int b) throws IOException {
    write(new byte[] {(byte) b}, 0, 1);
  }

  /** 写入字节数组切片。 */
  public void write(byte[] b) throws IOException {
    Objects.requireNonNull(b, "b");
    write(b, 0, b.length);
  }

  /** 写入指定范围的字节切片。 */
  public void write(byte[] b, int off, int len) throws IOException {
    Objects.requireNonNull(b, "b");
    if (off < 0 || len < 0 || off + len > b.length) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return;
    }

    for (int i = off; i < off + len; i++) {
      byte val = b[i];
      if (val == (byte) 0x0A) {
        lfCount++;
      }
      lastByte = val;
    }
    hasBytes = true;
    totalBytes += len;

    if (totalBytes > hardCapBytes) {
      tooLarge = true;
      return;
    }

    if (previewBuffer.size() < PREVIEW_MAX_BYTES * 2) {
      int take = Math.min(len, PREVIEW_MAX_BYTES * 2 - previewBuffer.size());
      previewBuffer.write(b, off, take);
    }

    if (!spilled) {
      if (totalBytes > inlineMaxBytes || totalLines() > inlineMaxLines) {
        spillToTempFile();
        fileOutputStream.write(b, off, len);
      } else {
        memoryBuffer.write(b, off, len);
      }
    } else {
      fileOutputStream.write(b, off, len);
    }
  }

  /** 当前累计的 UTF-8 字节数。 */
  public long totalBytes() {
    return totalBytes;
  }

  /** 当前累计的物理行数：空输出为 0；否则为 LF 数量加上未以 LF 结尾时的最后一行。 */
  public long totalLines() {
    if (!hasBytes) {
      return 0;
    }
    return lfCount + (lastByte != (byte) 0x0A ? 1 : 0);
  }

  /** 是否已转入临时文件。 */
  public boolean isSpilled() {
    return spilled;
  }

  /** 是否已超过硬上限。 */
  public boolean isTooLarge() {
    return tooLarge;
  }

  Path tempFile() {
    return tempFile;
  }

  /**
   * 构造终态结果。
   *
   * @param callId 调用标识
   * @param error 是否为错误退出
   * @param resourceStore 资源存储
   * @param mediaType 媒体类型
   * @return 终态 {@link EnvironmentCapabilityResult}
   */
  public EnvironmentCapabilityResult finish(
      String callId, boolean error, ResourceStore resourceStore, String mediaType)
      throws IOException {
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(resourceStore, "resourceStore");
    Objects.requireNonNull(mediaType, "mediaType");

    if (tooLarge) {
      String limitDesc =
          (hardCapBytes % (1024 * 1024) == 0)
              ? (hardCapBytes / (1024 * 1024)) + " MiB"
              : hardCapBytes + " bytes";
      return EnvironmentCapabilityResult.error(
          callId, "OUTPUT_TOO_LARGE: tool output exceeded " + limitDesc + " hard limit");
    }

    if (!spilled) {
      byte[] bytes = memoryBuffer.toByteArray();
      String text = new String(bytes, StandardCharsets.UTF_8);
      if (error) {
        return new EnvironmentCapabilityResult(
            callId, List.of(new TextResultContent(text)), true, "{}");
      }
      return EnvironmentCapabilityResult.text(callId, text);
    }

    try {
      if (fileOutputStream != null) {
        fileOutputStream.flush();
        fileOutputStream.close();
        fileOutputStream = null;
      }
      if (fileChannel != null) {
        fileChannel.close();
        fileChannel = null;
      }

      byte[] allBytes = Files.readAllBytes(tempFile);
      DaemonResourceRef stored = resourceStore.store(allBytes, mediaType);
      ResourceRef ref =
          new ResourceRef(
              stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
      String rawPreview = extractPreview(previewBuffer.toByteArray());
      TextArtifactMetadata metadata = new TextArtifactMetadata(totalBytes, totalLines());
      ResourceResultContent content = new ResourceResultContent(ref, rawPreview, metadata);
      return new EnvironmentCapabilityResult(callId, List.of(content), error, "{}");
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
        }
        tempFile = null;
      }
    }
  }

  static String extractPreview(byte[] initialBytes) {
    if (initialBytes == null || initialBytes.length == 0) {
      return "";
    }
    String text = new String(initialBytes, StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder();
    int lines = 0;
    int bytes = 0;
    boolean lineStarted = false;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int charCount = Character.charCount(cp);
      String cpStr = text.substring(i, i + charCount);
      int cpBytes = cpStr.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + cpBytes > PREVIEW_MAX_BYTES) {
        break;
      }
      if (cp == '\n') {
        lines++;
        sb.append(cpStr);
        bytes += cpBytes;
        lineStarted = false;
        if (lines >= PREVIEW_MAX_LINES) {
          break;
        }
      } else {
        if (!lineStarted) {
          if (lines >= PREVIEW_MAX_LINES) {
            break;
          }
          lineStarted = true;
        }
        sb.append(cpStr);
        bytes += cpBytes;
      }
      i += charCount;
    }
    return sb.toString();
  }

  private void spillToTempFile() throws IOException {
    spilled = true;
    tempFile = createPrivateTempFile();
    fileChannel =
        FileChannel.open(
            tempFile,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            LinkOption.NOFOLLOW_LINKS);
    fileOutputStream = Channels.newOutputStream(fileChannel);
    if (memoryBuffer.size() > 0) {
      memoryBuffer.writeTo(fileOutputStream);
      memoryBuffer.reset();
    }
  }

  private static Path createPrivateTempFile() throws IOException {
    Path temp;
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      FileAttribute<Set<PosixFilePermission>> attrs =
          PosixFilePermissions.asFileAttribute(
              Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      temp = Files.createTempFile("kk-spool-", ".tmp", attrs);
    } else {
      temp = Files.createTempFile("kk-spool-", ".tmp");
      File f = temp.toFile();
      f.setReadable(false, false);
      f.setReadable(true, true);
      f.setWritable(false, false);
      f.setWritable(true, true);
    }
    return temp;
  }

  @Override
  public void close() {
    if (fileOutputStream != null) {
      try {
        fileOutputStream.close();
      } catch (IOException ignored) {
      }
      fileOutputStream = null;
    }
    if (fileChannel != null) {
      try {
        fileChannel.close();
      } catch (IOException ignored) {
      }
      fileChannel = null;
    }
    if (tempFile != null) {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException ignored) {
      }
      tempFile = null;
    }
  }
}
