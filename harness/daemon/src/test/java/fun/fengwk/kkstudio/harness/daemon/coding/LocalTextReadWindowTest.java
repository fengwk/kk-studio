package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LocalTextReadWindow} 适配器定向测试：覆盖共享核心不便表达的本地侧边界。
 *
 * <p>窗口状态机本身（代理项、预算、截断元数据）由 {@code harness/common} 的 {@code TextReadWindowTest} 守护；本地端到端行为由 {@link
 * ReadCapabilityTest} 守护。这里只验证适配器独有的三件事：中断到 {@link InterruptedException} 的映射、按本地编码打开文件后的读取，以及 不重复剥离
 * BOM（{@link TextStreams} 已在字节层处理，正文里的第二个 {@code \uFEFF} 必须保留）。
 */
class LocalTextReadWindowTest {

  @TempDir Path workdir;

  /** 测试意图：扫描期间线程被中断时以 InterruptedException 结束，不把半截内容当作完整响应。 */
  @Test
  void interruptedScanFailsInsteadOfReturningPartialWindow() {
    AtomicBoolean returned = new AtomicBoolean();
    Reader interrupting =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            Thread.currentThread().interrupt();
            buffer[offset] = 'x';
            returned.set(true);
            return 1;
          }

          @Override
          public void close() {}
        };

    try {
      assertThrows(
          InterruptedException.class,
          () -> LocalTextReadWindow.read(interrupting, 1, 2000, null, "p", null));
      assertTrue(returned.get(), "读取必须真的发生过");
    } finally {
      Thread.interrupted();
    }
  }

  /** 测试意图：本地侧按文件前缀判定的编码打开严格解码流，UTF-16LE 文本与 BOM 都不进入正文。 */
  @Test
  void localEncodingReaderIsUsedAndBomIsNotStrippedTwice() throws Exception {
    Path utf16 = workdir.resolve("utf16.txt");
    Files.write(utf16, TextFileCodec.encode("alpha\r\nbeta\r\n", StandardCharsets.UTF_16LE, 2));
    assertEquals(
        String.join(
            "\n",
            "path: utf16.txt",
            "ends_with_newline: yes",
            "range: 1:1-2:4",
            "",
            "1|alpha",
            "2|beta"),
        LocalTextReadWindow.read(
            utf16,
            TextStreams.detectEncoding(TextStreams.probe(utf16)),
            1,
            2000,
            null,
            "utf16.txt",
            null));

    // 字节层 BOM 已剥离；正文里紧跟的第二个 BOM 是真实内容，适配器不得再剥一次。
    Path doubled = workdir.resolve("doubled.txt");
    byte[] body = "\uFEFFalpha\n".getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[body.length + 3];
    all[0] = (byte) 0xEF;
    all[1] = (byte) 0xBB;
    all[2] = (byte) 0xBF;
    System.arraycopy(body, 0, all, 3, body.length);
    Files.write(doubled, all);

    assertTrue(
        LocalTextReadWindow.read(
                doubled,
                TextStreams.detectEncoding(TextStreams.probe(doubled)),
                1,
                2000,
                null,
                "doubled.txt",
                null)
            .contains("1|\uFEFFalpha"));
  }
}
