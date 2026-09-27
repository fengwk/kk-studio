package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.harness.common.text.TextReadWindow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 受管文本读取的薄适配器：严格 UTF-8 解码、首个字符 BOM 剥离、扫描超时与中断映射，其余（分页窗口、正文预算、截断元数据与编号正文）全部交给共享核心 {@link
 * TextReadWindow}，与本地 {@code fs.read} 共用同一份状态机。
 *
 * <ul>
 *   <li>严格 UTF-8 解码：非法字节序列与 NUL 一样按“看似二进制”失败；首个 {@code \uFEFF} 只作为 BOM 剥离一次，正文里后续出现的 {@code \uFEFF}
 *       必须保留。
 *   <li>扫描预算 {@link #MAX_SCAN_NANOS} 与线程中断在每块读取前后检查，命中即以 {@link PlatformReadException}
 *       结束，不返回伪造的空文本。
 *   <li>底层 IO 失败与内容非法都收敛为 {@link PlatformReadException}；存储截止抛出的 {@code StorageReadTimeoutException}
 *       等未检查异常原样穿过，保持上层对“超时”与“内容非法”的区分。
 *   <li>输入流由调用方负责关闭，本适配器只消费。
 * </ul>
 */
public final class ReadTextWindow {

  /** 单次读取的扫描预算：读取到期或线程被中断都以可区分的失败结束，不返回伪造的空文本。 */
  private static final long MAX_SCAN_NANOS = TimeUnit.SECONDS.toNanos(30);

  private ReadTextWindow() {}

  /** 按默认 LSP 状态（{@code unsupported}）格式化原始 UTF-8 字节。 */
  public static String format(
      byte[] bytes, Integer offset, Integer limit, Integer columnOffset, String displayPath) {
    return format(bytes, offset, limit, columnOffset, displayPath, "unsupported");
  }

  /** 格式化原始 UTF-8 字节；{@code bytes} 为空数组时表示空资源。 */
  public static String format(
      byte[] bytes,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus) {
    Objects.requireNonNull(bytes, "bytes");
    return format(
        new ByteArrayInputStream(bytes), offset, limit, columnOffset, displayPath, lspStatus);
  }

  /** 消费但不关闭输入流；调用方负责在解析成功或失败后关闭资源。 */
  public static String format(
      InputStream stream,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(displayPath, "displayPath");
    Objects.requireNonNull(lspStatus, "lspStatus");

    if (offset != null && offset < 1) {
      throw new PlatformReadException("offset must be a positive integer");
    }
    if (columnOffset != null && columnOffset < 1) {
      throw new PlatformReadException("column_offset must be a positive integer");
    }
    int resolvedOffset = offset == null ? 1 : offset;
    int resolvedLimit = limit == null ? TextReadWindow.DEFAULT_LIMIT : limit;
    if (resolvedLimit < 1 || resolvedLimit > TextReadWindow.MAX_LIMIT) {
      throw new PlatformReadException(
          "limit must be a positive integer <= " + TextReadWindow.MAX_LIMIT);
    }

    long startedAt = System.nanoTime();
    Runnable checkpoint =
        () -> {
          if (Thread.currentThread().isInterrupted()
              || System.nanoTime() - startedAt > MAX_SCAN_NANOS) {
            throw new PlatformReadException(
                "resource read interrupted or timed out: " + displayPath);
          }
        };
    try {
      return TextReadWindow.read(
          TextReadWindow.withoutLeadingBom(strictUtf8Reader(stream)),
          resolvedOffset,
          resolvedLimit,
          columnOffset,
          displayPath,
          lspStatus,
          checkpoint);
    } catch (IOException error) {
      throw new PlatformReadException(
          "file appears to be binary or cannot be read: " + displayPath, error);
    } catch (IllegalArgumentException error) {
      throw new PlatformReadException(error.getMessage(), error);
    }
  }

  private static Reader strictUtf8Reader(InputStream stream) {
    return new InputStreamReader(
        stream,
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
  }
}
