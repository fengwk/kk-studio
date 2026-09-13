package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 {@link ReadCapability}、{@link WriteCapability}、{@link EditCapability} 与 {@link TextFileCodec}
 * 的核心契约、边界条件与覆盖率。
 */
class ReadWriteEditCapabilitiesTest {

  @TempDir Path workdir;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private CodingToolsConfig config() {
    return new CodingToolsConfig(
        workdir, 2000, 50 * 1024, "bash", new InMemoryResourceStore(), "echo", "javap");
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String argumentsJson)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EnvironmentCapabilityResult> resultRef = new AtomicReference<>();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-1", argumentsJson),
            Duration.ofSeconds(10)),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            resultRef.set(result);
            latch.countDown();
          }

          @Override
          public void onError(Throwable error) {
            resultRef.set(EnvironmentCapabilityResult.error("call-1", error.getMessage()));
            latch.countDown();
          }
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    return resultRef.get();
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static String json(String str) {
    return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** 验证 ReadCapability 读取目录的分页、边界越界提示与展示格式。 */
  @Test
  void readDirectoryPaginationAndBoundaries() throws Exception {
    Path dir = workdir.resolve("sub");
    Files.createDirectories(dir);
    Files.createFile(dir.resolve("fileA.txt"));
    Files.createFile(dir.resolve("fileB.txt"));
    Files.createDirectory(dir.resolve("nestedDir"));

    ReadCapability read = new ReadCapability(config(), executor);

    // 正常分页
    EnvironmentCapabilityResult p1 =
        invoke(
            read,
            "{\"path\":\"sub\",\"offset\":1,\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p1.error());
    String t1 = text(p1);
    assertTrue(t1.contains("path: sub"));
    assertTrue(t1.contains("kind: directory"));
    assertTrue(t1.contains("fileA.txt"));
    assertTrue(t1.contains("fileB.txt"));
    assertTrue(t1.contains("Showing entries 1-2 of 3"));

    // offset 超限返回空
    EnvironmentCapabilityResult pEmpty =
        invoke(
            read,
            "{\"path\":\"sub\",\"offset\":10,\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(pEmpty.error());
    assertTrue(text(pEmpty).contains("[Showing 0 entries of 3.]"));
  }

  /** 验证 ReadCapability 文本读取超过 2000 code points 截断标记、offset>total 行数为空提示。 */
  @Test
  void readTextLineTruncationAndOffsetBeyondTotal() throws Exception {
    Path textFile = workdir.resolve("longline.txt");
    String longLine = "a".repeat(2500);
    Files.writeString(textFile, "short\n" + longLine + "\nend\n");

    ReadCapability read = new ReadCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            read,
            "{\"path\":\"longline.txt\",\"offset\":1,\"limit\":10,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("(line truncated to 2000 chars)"));
    assertTrue(out.contains("Note: one or more lines were truncated to 2000 characters."));

    // offset > totalLines 返回空窗口
    EnvironmentCapabilityResult beyond =
        invoke(
            read,
            "{\"path\":\"longline.txt\",\"offset\":100,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyond.error());
    assertTrue(text(beyond).contains("[Showing 0 lines of 3.]"));
  }

  /** 验证 ReadCapability 识别支持的图片 MIME 并返回 ResourceResultContent。 */
  @Test
  void readDetectsSupportedImageMimes() throws Exception {
    ReadCapability read = new ReadCapability(config(), executor);

    // JPEG
    Path jpg = workdir.resolve("test.jpg");
    Files.write(jpg, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3});
    EnvironmentCapabilityResult rJpg =
        invoke(read, "{\"path\":\"test.jpg\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rJpg.error());
    assertTrue(rJpg.contents().getFirst() instanceof ResourceResultContent);
    assertEquals(
        "image/jpeg", ((ResourceResultContent) rJpg.contents().getFirst()).resource().mediaType());

    // GIF
    Path gif = workdir.resolve("test.gif");
    Files.write(gif, new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2});
    EnvironmentCapabilityResult rGif =
        invoke(read, "{\"path\":\"test.gif\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rGif.error());
    assertEquals(
        "image/gif", ((ResourceResultContent) rGif.contents().getFirst()).resource().mediaType());

    // WEBP
    Path webp = workdir.resolve("test.webp");
    Files.write(webp, new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
    EnvironmentCapabilityResult rWebp =
        invoke(read, "{\"path\":\"test.webp\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rWebp.error());
    assertEquals(
        "image/webp", ((ResourceResultContent) rWebp.contents().getFirst()).resource().mediaType());
  }

  /** 验证 ReadCapability 针对各种语言后缀检测 LSP 语言。 */
  @Test
  void readDetectsLspLanguages() {
    assertEquals("java", ReadCapability.detectLanguage(Path.of("App.java")));
    assertEquals("typescript", ReadCapability.detectLanguage(Path.of("index.ts")));
    assertEquals("javascript", ReadCapability.detectLanguage(Path.of("index.js")));
    assertEquals("python", ReadCapability.detectLanguage(Path.of("script.py")));
    assertEquals("go", ReadCapability.detectLanguage(Path.of("main.go")));
    assertEquals("rust", ReadCapability.detectLanguage(Path.of("lib.rs")));
    assertEquals("c", ReadCapability.detectLanguage(Path.of("main.c")));
    assertEquals("c", ReadCapability.detectLanguage(Path.of("header.h")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("source.cpp")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("source.cc")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("header.hpp")));
  }

  /** 验证 WriteCapability 保留已存在文件的换行风格与编码。 */
  @Test
  void writePreservesExistingLineEndingsAndRejectsDirectory() throws Exception {
    WriteCapability write = new WriteCapability(config(), executor);

    // CRLF
    Path crlfFile = workdir.resolve("crlf.txt");
    Files.writeString(crlfFile, "line1\r\nline2\r\n");
    EnvironmentCapabilityResult resCrlf =
        invoke(
            write,
            "{\"path\":\"crlf.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCrlf.error());
    assertEquals("a\r\nb\r\n", Files.readString(crlfFile));

    // CR
    Path crFile = workdir.resolve("cr.txt");
    Files.write(crFile, "line1\rline2\r".getBytes(StandardCharsets.UTF_8));
    EnvironmentCapabilityResult resCr =
        invoke(
            write,
            "{\"path\":\"cr.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCr.error());
    assertArrayEquals("a\rb\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(crFile));

    // 拒绝目录作为写目标
    Path dir = workdir.resolve("mydir");
    Files.createDirectory(dir);
    EnvironmentCapabilityResult resDir =
        invoke(
            write,
            "{\"path\":\"mydir\",\"content\":\"x\",\"workdir\":" + json(workdir.toString()) + "}");
    assertTrue(resDir.error());
    assertTrue(text(resDir).contains("path is a directory"));

    // 行尾样式检测辅助函数
    assertEquals("\n", WriteCapability.detectLineEnding(null));
    assertEquals("mixed", WriteCapability.detectLineEnding("a\r\nb\n"));
    assertEquals("\r\n", WriteCapability.detectLineEnding("a\r\nb\r\n"));
    assertEquals("\r", WriteCapability.detectLineEnding("a\rb\r"));
    assertEquals("\n", WriteCapability.detectLineEnding("a\nb\n"));
  }

  /** 验证 EditCapability 生成上下文 diff 并拒绝非法输入（错误不回显 old_string）。 */
  @Test
  void editContextualDiffAndErrorsDoNotEchoOldString() throws Exception {
    EditCapability edit = new EditCapability(config(), executor);
    Path target = workdir.resolve("sample.txt");
    Files.writeString(target, "line 1\nline 2\nline 3\nline 4\nline 5\n");

    // 替换单行成功，输出上下文 diff
    EnvironmentCapabilityResult res =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"line 3\",\"new_string\":\"LINE 3\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String diff = text(res);
    assertTrue(diff.contains("Edited sample.txt successfully."));
    assertTrue(diff.contains("- 3|line 3"));
    assertTrue(diff.contains("+ 3|LINE 3"));
    assertTrue(diff.contains(" 2|line 2"));
    assertTrue(diff.contains(" 4|line 4"));

    // 未找到匹配：错误信息不得回显 old_string 的具体内容
    EnvironmentCapabilityResult notFound =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"SECRET_PASSWORD_123\",\"new_string\":\"x\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(notFound.error());
    assertTrue(text(notFound).contains("Could not find old_string"));
    assertFalse(text(notFound).contains("SECRET_PASSWORD_123"));
  }

  /** 验证 TextFileCodec 处理 UTF-16 编码、BOM、NUL 以及无法编码文本的拒绝。 */
  @Test
  void textFileCodecHandlesUtf16AndStrictValidation() {
    // UTF-16LE with BOM
    byte[] utf16le = new byte[] {(byte) 0xff, (byte) 0xfe, 'h', 0, 'i', 0};
    TextFileCodec.Decoded decodedLe = TextFileCodec.decode(utf16le);
    assertEquals("hi", decodedLe.text());
    assertEquals(StandardCharsets.UTF_16LE, decodedLe.charset());
    assertEquals(2, decodedLe.bomLength());

    byte[] encodedLe = TextFileCodec.encode("hi", StandardCharsets.UTF_16LE, 2);
    assertArrayEquals(utf16le, encodedLe);

    // UTF-16BE with BOM
    byte[] utf16be = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'h', 0, 'i'};
    TextFileCodec.Decoded decodedBe = TextFileCodec.decode(utf16be);
    assertEquals("hi", decodedBe.text());
    assertEquals(StandardCharsets.UTF_16BE, decodedBe.charset());
    assertEquals(2, decodedBe.bomLength());

    // NUL 字符拒绝
    byte[] hasNul = new byte[] {'a', 0, 'b'};
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(hasNul));

    // 非法 UTF-8 解码拒绝
    byte[] invalidUtf8 = new byte[] {(byte) 0xc0, (byte) 0xaf};
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(invalidUtf8));

    // 无法编码字符拒绝（如 US-ASCII 编码包含非 ASCII 字符）
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode("你好", StandardCharsets.US_ASCII, 0));

    // 空校验
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode(null, StandardCharsets.UTF_8, 0));
  }
}
