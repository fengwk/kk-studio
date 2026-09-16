package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 生产者端有界输出捕获器：内联小输出，本地落盘大文本，从不因为输出体积终止进程。
 *
 * <p>内联阈值（默认 ≤ 50 KiB 且 ≤ 2000 行）之内保持完整内存缓冲；跨越阈值后只在 {@code <data-dir>/resources/staging} 建立
 * owner-only 中转文件并流式写入，{@link #finish} 时原子发布为 durable 全文。本地写入失败（磁盘满、权限等）只降级为有界预览，不抛出、不终止子进程
 * ——输出体积或本地磁盘状态永远不是杀进程的理由。
 *
 * <p>达到捕获预算（默认 1 GiB）后停止文件捕获，但继续统计总数并继续接收输出；终态明确报告“捕获被截断”，并把已捕获部分作为可读文件发布。
 *
 * <p>无论是否落盘，终态都只返回一个 {@link TextResultContent}：完整小输出，或 head/tail 有界预览加绝对路径、总字节/行数与显式 read/grep
 * 指引。文本不是 Resource，不经过 ResourceStore。
 */
public final class OutputSpool implements AutoCloseable {

  public static final int INLINE_MAX_BYTES = 50 * 1024;
  public static final int INLINE_MAX_LINES = 2000;

  /** 预览 head/tail 各自的字节上界；单侧 4 KiB 使整体预览远小于 256 KiB 的单条 partial 上限。 */
  public static final int PREVIEW_HEAD_MAX_BYTES = 4 * 1024;

  public static final int PREVIEW_TAIL_MAX_BYTES = 8 * 1024;

  private final TextOutputStore store;
  private final String callId;
  private final int inlineMaxBytes;
  private final int inlineMaxLines;
  private final long captureBudgetBytes;

  private final ByteArrayOutputStream memoryBuffer;
  private final ByteArrayOutputStream headBuffer;
  private final ByteTailBuffer tailBuffer;

  private long totalBytes;
  private long lineBreaks;
  private byte lastByte;
  private boolean hasBytes;
  private boolean spilled;
  private boolean captureTruncated;
  private boolean captureFailed;

  private Path stagingFile;
  private long capturedBytes;
  private FileChannel fileChannel;
  private OutputStream fileOutputStream;

  /** 使用默认内联阈值与默认捕获预算。 */
  public OutputSpool(TextOutputStore store, String callId) {
    this(store, callId, INLINE_MAX_BYTES, INLINE_MAX_LINES, store.captureBudgetBytes());
  }

  /** 使用显式阈值与捕获预算，便于确定性边界测试。 */
  public OutputSpool(
      TextOutputStore store,
      String callId,
      int inlineMaxBytes,
      int inlineMaxLines,
      long captureBudgetBytes) {
    this.store = Objects.requireNonNull(store, "store");
    this.callId = Objects.requireNonNull(callId, "callId");
    if (inlineMaxBytes <= 0 || inlineMaxLines <= 0 || captureBudgetBytes <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    this.inlineMaxBytes = inlineMaxBytes;
    this.inlineMaxLines = inlineMaxLines;
    this.captureBudgetBytes = captureBudgetBytes;
    this.memoryBuffer = new ByteArrayOutputStream(Math.min(inlineMaxBytes, 8192));
    this.headBuffer = new ByteArrayOutputStream(PREVIEW_HEAD_MAX_BYTES);
    this.tailBuffer = new ByteTailBuffer(PREVIEW_TAIL_MAX_BYTES);
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

  /** 写入指定范围的字节切片；任何本地 IO 失败都只降级为有界预览。 */
  public void write(byte[] b, int off, int len) throws IOException {
    Objects.requireNonNull(b, "b");
    if (off < 0 || len < 0 || off + len > b.length) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return;
    }

    for (int index = off; index < off + len; index++) {
      byte value = b[index];
      if (value == (byte) 0x0D) {
        lineBreaks++;
      } else if (value == (byte) 0x0A && lastByte != (byte) 0x0D) {
        // CRLF 只记一次换行：LF 紧跟 CR 时该换行已由前一个字节计数。
        lineBreaks++;
      }
      lastByte = value;
    }
    hasBytes = true;
    totalBytes += len;

    captureHead(b, off, len);
    tailBuffer.append(b, off, len);

    if (captureTruncated || captureFailed) {
      // 达到捕获预算或写入失败后不再落盘，但继续计数并保留尾部预览。
      return;
    }

    if (!spilled) {
      if (totalBytes <= inlineMaxBytes && totalLines() <= inlineMaxLines) {
        memoryBuffer.write(b, off, len);
        return;
      }
      if (!spillToStagingFile()) {
        return;
      }
    }
    appendToFile(b, off, len);
  }

  /** 当前累计的 UTF-8 字节数；达到捕获预算后仍继续计数，使终态报告准确。 */
  public long totalBytes() {
    return totalBytes;
  }

  /**
   * 当前累计的物理行数：空输出为 0；否则为换行符数量加上未以换行结束时的最后一行。
   *
   * <p>换行判定与 `read`/`grep` 使用的 {@link TextStreams} 完全一致：CR、LF 与 CRLF 都记作一次换行（CRLF 只记一次），
   * 因此终态报告的“总行数”与随后对同一文件分页读取时报告的总行数相同。
   */
  public long totalLines() {
    if (!hasBytes) {
      return 0;
    }
    boolean endsWithTerminator = lastByte == (byte) 0x0A || lastByte == (byte) 0x0D;
    return lineBreaks + (endsWithTerminator ? 0 : 1);
  }

  /** 是否已转入本地文件。 */
  public boolean isSpilled() {
    return spilled;
  }

  /** 是否因达到捕获预算而停止文件捕获（进程仍在正常执行）。 */
  public boolean isCaptureTruncated() {
    return captureTruncated;
  }

  /** 本地文件写入是否失败（只影响全文可读性，不影响进程）。 */
  public boolean isCaptureFailed() {
    return captureFailed;
  }

  /** 已写入本地文件的字节数；达到预算后不再增长。 */
  public long capturedBytes() {
    return capturedBytes;
  }

  /** 当前尚未发布的中转文件；未转入文件或已发布时为 {@code null}。 */
  Path stagingFile() {
    return stagingFile;
  }

  /**
   * 构造终态结果：小输出内联返回全文；跨阈值时发布 durable 全文并返回 head/tail 预览、绝对路径与 read/grep 指引。
   *
   * @param error 是否为错误退出
   */
  public EnvironmentCapabilityResult finish(boolean error) {
    if (!spilled) {
      String text = new String(memoryBuffer.toByteArray(), StandardCharsets.UTF_8);
      if (error) {
        return new EnvironmentCapabilityResult(
            callId, List.of(new TextResultContent(text)), true, "{}");
      }
      return EnvironmentCapabilityResult.text(callId, text);
    }

    Path published = completeAndPublish();
    String preview = buildPreviewText(published);
    String detailsJson = buildDetailsJson(published);
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextResultContent(preview)), error, detailsJson);
  }

  /** 关闭文件并原子发布 durable 全文；失败时返回 {@code null} 表示只有预览可用。 */
  private Path completeAndPublish() {
    Path staging = stagingFile;
    if (staging == null) {
      return null;
    }
    try {
      closeFileStreams();
    } catch (IOException error) {
      return abandonStaging(staging);
    }
    try {
      Path target = store.publish(staging);
      stagingFile = null;
      return target;
    } catch (IOException error) {
      return abandonStaging(staging);
    }
  }

  private Path abandonStaging(Path staging) {
    captureFailed = true;
    // 关闭可能仍然打开的文件流：发布失败不能顺带泄漏文件描述符。
    closeFileStreamsQuietly();
    store.deleteStagingQuietly(staging);
    stagingFile = null;
    return null;
  }

  /** 预览文本：head/tail 有界片段加省略说明，再接完整事实与 read/grep 指引。 */
  private String buildPreviewText(Path published) {
    byte[] headAll = headBuffer.toByteArray();
    byte[] tailAll = tailBuffer.toByteArray();

    // 两个字节上界都可能落在多字节字符内部：按完整字符边界裁剪后再解码，预览不产生替换字符，
    // 省略字节数也因此仍精确等于被省略的原始字节数。
    int headLength = Utf8StreamDecoder.alignToCharacterBoundary(headAll, headAll.length);
    int tailBegin = Utf8StreamDecoder.leadingCharacterBoundary(tailAll);
    int tailEnd = Utf8StreamDecoder.alignToCharacterBoundary(tailAll, tailAll.length);

    // 总量不超过缓冲容量之和时 head 与 tail 覆盖同一段内容：扣除重叠，避免重复展示同一批字节。
    long tailAbsStart = totalBytes - tailAll.length + tailBegin;
    int skip = (int) Math.max(0, Math.min((long) tailEnd - tailBegin, headLength - tailAbsStart));
    if (skip >= tailEnd - tailBegin) {
      tailBegin = 0;
      tailEnd = 0;
    } else {
      tailBegin += skip;
    }

    long omitted = tailAbsStart + skip - headLength;
    String head = decode(Arrays.copyOf(headAll, headLength));
    String tail = decode(Arrays.copyOfRange(tailAll, tailBegin, tailEnd));

    StringBuilder preview = new StringBuilder();
    preview.append(head);
    if (omitted > 0) {
      if (!head.isEmpty() && !head.endsWith("\n")) {
        preview.append('\n');
      }
      preview
          .append("[... ")
          .append(omitted)
          .append(" bytes omitted here; the middle of the output is not shown ...]\n");
    }
    preview.append(tail);
    if (!preview.isEmpty() && preview.charAt(preview.length() - 1) != '\n') {
      preview.append('\n');
    }
    preview.append('\n').append(buildFooter(published));
    return preview.toString();
  }

  /** 终态事实与指引：路径、总字节/行数、捕获完整性，以及可执行的下一步。 */
  private String buildFooter(Path published) {
    StringBuilder footer = new StringBuilder();
    if (published == null) {
      footer
          .append("[Full output (")
          .append(totalBytes)
          .append(" bytes, ")
          .append(totalLines())
          .append(
              " lines) could not be saved to local storage; only this bounded preview is available.");
      if (captureTruncated) {
        footer.append(' ').append(captureTruncationNotice());
      }
      return footer.append(']').toString();
    }
    footer
        .append("[Full output: ")
        .append(totalBytes)
        .append(" bytes, ")
        .append(totalLines())
        .append(" lines. Saved to: ")
        .append(published)
        .append('\n')
        .append("Use read with offset/limit to page through the file, or grep to search it.]");
    if (captureTruncated) {
      footer.append(' ').append(captureTruncationNotice());
    }
    return footer.toString();
  }

  private String captureTruncationNotice() {
    return "Capture stopped at the local "
        + captureBudgetBytes
        + "-byte daemon budget; the command itself ran to completion and its exit code is meaningful.";
  }

  /** detailsJson 承载与预览相同的事实，供调用方机器判定。 */
  private String buildDetailsJson(Path published) {
    ObjectNode root = AbstractCodingCapability.OBJECT_MAPPER.createObjectNode();
    ObjectNode textOutput = root.putObject("textOutput");
    textOutput.put("totalBytes", totalBytes);
    textOutput.put("totalLines", totalLines());
    textOutput.put("capturedBytes", capturedBytes);
    textOutput.put("captureTruncated", captureTruncated);
    textOutput.put("captureFailed", published == null);
    if (published != null) {
      textOutput.put("path", published.toString());
      textOutput.put("readHint", "read pages the file with offset/limit; grep searches it");
    }
    return root.toString();
  }

  /** 截断恢复快照使用的最新有界尾部文本（不含终态 footer）。 */
  String captureTruncatedTailPreview() {
    byte[] tail = tailBuffer.toByteArray();
    // 尾部窗口可能从字符内部开始：跳过前导续字节，快照不产生替换字符。
    int begin = Utf8StreamDecoder.leadingCharacterBoundary(tail);
    int end = Utf8StreamDecoder.alignToCharacterBoundary(tail, tail.length);
    return decode(Arrays.copyOfRange(tail, begin, Math.max(begin, end)));
  }

  private static String decode(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private void captureHead(byte[] b, int off, int len) {
    if (headBuffer.size() >= PREVIEW_HEAD_MAX_BYTES) {
      return;
    }
    int take = Math.min(len, PREVIEW_HEAD_MAX_BYTES - headBuffer.size());
    headBuffer.write(b, off, take);
  }

  private boolean spillToStagingFile() {
    spilled = true;
    try {
      stagingFile = store.createStagingFile(callId);
    } catch (IOException error) {
      return failCapture(null);
    }
    try {
      fileChannel =
          FileChannel.open(stagingFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      fileOutputStream = Channels.newOutputStream(fileChannel);
      if (memoryBuffer.size() > 0) {
        byte[] buffered = memoryBuffer.toByteArray();
        memoryBuffer.reset();
        // 已缓冲字节同样受捕获预算约束：预算必须是从第一个字节起就生效的上界。
        captureBytes(buffered, 0, buffered.length);
      }
      return true;
    } catch (IOException error) {
      return failCapture(stagingFile);
    }
  }

  /** 本地存储失败只影响全文可读性：保留有界预览，绝不因此终止进程。 */
  private boolean failCapture(Path staging) {
    captureFailed = true;
    // 先关闭可能已打开的文件描述符，再丢弃中转文件；失败路径不得泄漏句柄或留下幽灵文件。
    closeFileStreamsQuietly();
    store.deleteStagingQuietly(staging);
    stagingFile = null;
    return false;
  }

  private void appendToFile(byte[] b, int off, int len) {
    captureBytes(b, off, len);
  }

  /**
   * 把字节写入本地文件并严格维护捕获预算。
   *
   * <p>预算耗尽后只停止写文件并标记截断：调用方继续计数、继续排空，进程不受影响。
   */
  private void captureBytes(byte[] b, int off, int len) {
    long remaining = captureBudgetBytes - capturedBytes;
    if (remaining <= 0) {
      captureTruncated = true;
      closeFileStreamsQuietly();
      return;
    }
    int accepted = (int) Math.min(len, remaining);
    try {
      fileOutputStream.write(b, off, accepted);
      capturedBytes += accepted;
      if (accepted < len) {
        captureTruncated = true;
        closeFileStreamsQuietly();
      }
    } catch (IOException error) {
      captureFailed = true;
      closeFileStreamsQuietly();
    }
  }

  /**
   * 关闭文件流并强制落盘。
   *
   * <p>{@code Channels.newOutputStream} 直接写通道且其 {@code close} 会关闭通道，因此 {@code force} 必须在关闭之前执行；
   * 即使落盘失败也仍然继续关闭，避免把失败变成文件描述符泄漏。
   */
  private void closeFileStreams() throws IOException {
    IOException failure = null;
    if (fileChannel != null) {
      try {
        fileChannel.force(true);
      } catch (IOException error) {
        failure = error;
      }
    }
    if (fileOutputStream != null) {
      try {
        fileOutputStream.flush();
        fileOutputStream.close();
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        }
      }
      fileOutputStream = null;
    }
    if (fileChannel != null) {
      try {
        fileChannel.close();
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        }
      }
      fileChannel = null;
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** 关闭文件流但不改变结果：已捕获部分仍会保留并发布。仅用于截断或写入失败之后的收尾。 */
  private void closeFileStreamsQuietly() {
    try {
      closeFileStreams();
    } catch (IOException ignored) {
      // 已捕获部分仍可发布；关闭失败只影响后续写入，而后续写入已经被禁止。
    }
  }

  @Override
  public void close() {
    closeFileStreamsQuietly();
    if (stagingFile != null) {
      store.deleteStagingQuietly(stagingFile);
      stagingFile = null;
    }
  }
}
