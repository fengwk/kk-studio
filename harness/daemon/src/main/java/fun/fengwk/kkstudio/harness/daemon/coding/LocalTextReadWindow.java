package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.common.text.TextReadWindow;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;

/**
 * 本地 {@code fs.read} 的薄适配器：把本地文件按既有编码打开为字符流，交给共享的 {@link TextReadWindow} 核心。
 *
 * <p>适配器只负责本地特有的三件事：按 {@link TextStreams.Encoding} 打开严格解码的流、把扫描检查点抛出的中断转换为 {@link
 * InterruptedException} 以便调用方收尾、以及不重复剥离 BOM —— {@link TextStreams} 已在字节层跳过 BOM，若这里再按字符剥离就会吞掉正文里真正的
 * {@code \uFEFF}。
 */
final class LocalTextReadWindow {

  /** 缺省返回行数上限。 */
  static final int DEFAULT_LIMIT = TextReadWindow.DEFAULT_LIMIT;

  /** 最大返回行数上限。 */
  static final int MAX_LIMIT = TextReadWindow.MAX_LIMIT;

  private LocalTextReadWindow() {}

  /**
   * 读取并格式化 {@code path} 的文本窗口。
   *
   * @param path 目标文件
   * @param encoding 由文件前缀判定的严格解码编码（已含字节层 BOM 剥离）
   * @param offset 已校验的 1-based 起始行
   * @param limit 已校验的最大行数
   * @param columnOffset 已校验的起始行内 1-based 码点偏移，{@code null} 表示从第 1 列开始
   * @param displayPath header 中展示的路径
   * @param lspStatus LSP 状态；{@code null} 或 {@code unsupported} 时不输出
   * @throws IllegalArgumentException 目标行越界列或内容看似二进制
   * @throws IOException 读取失败
   * @throws InterruptedException 扫描期间线程被中断
   */
  static String read(
      Path path,
      TextStreams.Encoding encoding,
      int offset,
      int limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus)
      throws IOException, InterruptedException {
    try (Reader reader = encoding.openReader(path)) {
      return read(reader, offset, limit, columnOffset, displayPath, lspStatus);
    }
  }

  /** 已解码字符流上的窗口投影；参数由调用方校验。 */
  static String read(
      Reader reader,
      int offset,
      int limit,
      Integer columnOffset,
      String displayPath,
      String lspStatus)
      throws IOException, InterruptedException {
    try {
      return TextReadWindow.read(
          reader,
          offset,
          limit,
          columnOffset,
          displayPath,
          lspStatus,
          LocalTextReadWindow::checkInterrupted);
    } catch (ScanInterruptedException error) {
      throw new InterruptedException(error.getMessage());
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new ScanInterruptedException("text scan was interrupted");
    }
  }

  /** 核心的检查点是 {@link Runnable}：checked 中断在此以可解包的未检查异常穿过核心，由适配器换回 {@link InterruptedException}。 */
  private static final class ScanInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private ScanInterruptedException(String message) {
      super(message);
    }
  }
}
