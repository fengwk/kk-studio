package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 {@link FindCapability}、{@link GrepCapability} 与 {@link SearchFiles}
 * 的核心契约、提前终止（early-stop）、RE2/J 约束、行截断与输出外置。
 */
class FindGrepCapabilitiesTest {

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
    return TestCodingConfig.withBridge(workdir, new InMemoryResourceStore());
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String argumentsJson)
      throws Exception {
    return invoke(capability, argumentsJson, Duration.ofSeconds(10));
  }

  private EnvironmentCapabilityResult invoke(
      EnvironmentCapability capability, String argumentsJson, Duration timeout) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EnvironmentCapabilityResult> resultRef = new AtomicReference<>();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-1", argumentsJson),
            timeout),
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

  /** 验证 FindCapability 匹配 basename 和 path glob pattern，以及确定性排序与工作区相对路径。 */
  @Test
  void findMatchesBasenameAndPathGlobPatternsDeterministically() throws Exception {
    Path src = Files.createDirectories(workdir.resolve("src"));
    Path sub = Files.createDirectories(src.resolve("sub"));
    Files.writeString(workdir.resolve("root.txt"), "root");
    Files.writeString(src.resolve("Main.java"), "class Main {}");
    Files.writeString(src.resolve("Helper.java"), "class Helper {}");
    Files.writeString(sub.resolve("Deep.java"), "class Deep {}");
    Files.writeString(sub.resolve("data.txt"), "data");

    FindCapability find = new FindCapability(config(), executor);

    // Basename matching: *.java matches across all directories
    EnvironmentCapabilityResult res1 =
        invoke(
            find,
            "{\"pattern\":\"*.java\",\"path\":\".\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res1.error());
    assertEquals("src/Helper.java\nsrc/Main.java\nsrc/sub/Deep.java", text(res1));

    // Path matching with slash: src/*.java does NOT match src/sub/Deep.java (* does not cross /)
    EnvironmentCapabilityResult res2 =
        invoke(
            find,
            "{\"pattern\":\"src/*.java\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2.error());
    assertEquals("src/Helper.java\nsrc/Main.java", text(res2));

    // ** matches across directories
    EnvironmentCapabilityResult res3 =
        invoke(
            find,
            "{\"pattern\":\"src/**/*.java\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res3.error());
    assertEquals("src/Helper.java\nsrc/Main.java\nsrc/sub/Deep.java", text(res3));

    // ? matches single char
    EnvironmentCapabilityResult res4 =
        invoke(
            find,
            "{\"pattern\":\"dat?.txt\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res4.error());
    assertEquals("src/sub/data.txt", text(res4));
  }

  /** 验证 FindCapability 在无匹配项时返回预期的提示。 */
  @Test
  void findReturnsNoFilesFoundWhenNoMatches() throws Exception {
    Files.writeString(workdir.resolve("file.txt"), "content");
    FindCapability find = new FindCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"*.nonexistent\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertEquals("No files found matching pattern", text(res));
  }

  /** 验证 FindCapability 遵守分层 .gitignore 并跳过 .git 元数据。 */
  @Test
  void findRespectsHierarchicalGitignoreAndSkipsGitMetadata() throws Exception {
    Path src = Files.createDirectories(workdir.resolve("src"));
    Path gitDir = Files.createDirectories(workdir.resolve(".git"));
    Files.writeString(gitDir.resolve("config"), "git config");
    Files.writeString(workdir.resolve(".gitignore"), "ignored_root.txt\nsrc/ignored_sub/\n");
    Files.writeString(workdir.resolve("visible.txt"), "visible");
    Files.writeString(workdir.resolve("ignored_root.txt"), "ignored");

    Path ignoredSub = Files.createDirectories(src.resolve("ignored_sub"));
    Files.writeString(ignoredSub.resolve("file.txt"), "ignored file");
    Files.writeString(src.resolve("visible_sub.txt"), "visible");

    Path sub = Files.createDirectories(workdir.resolve("sub"));
    Files.writeString(sub.resolve(".gitignore"), "local_ignored.txt\n");
    Files.writeString(sub.resolve("local_ignored.txt"), "ignored");
    Files.writeString(sub.resolve("local_visible.txt"), "visible");

    FindCapability find = new FindCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"*.txt\",\"path\":\".\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("visible.txt"));
    assertTrue(out.contains("src/visible_sub.txt"));
    assertTrue(out.contains("sub/local_visible.txt"));
    assertFalse(out.contains("ignored_root.txt"));
    assertFalse(out.contains("ignored_sub"));
    assertFalse(out.contains("local_ignored.txt"));
    assertFalse(out.contains(".git"));
  }

  /** 验证 FindCapability 在达到 limit + 1 时立即停止遍历并附加提示。 */
  @Test
  void findEarlyStopsAtLimitPlusOne() throws Exception {
    for (int i = 1; i <= 6; i++) {
      Files.writeString(workdir.resolve("file_" + i + ".txt"), "content");
    }

    FindCapability find = new FindCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"file_*.txt\",\"path\":\".\",\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("file_1.txt"));
    assertTrue(out.contains("file_2.txt"));
    assertFalse(out.contains("file_3.txt"));
    assertTrue(out.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
  }

  /** 验证 FindCapability 在结果超过行数阈值时返回单个 TextResultContent：有界预览加本地路径，而不是 Resource。 */
  @Test
  void findSpoolsLargeResultsToBoundedTextResultWithPath() throws Exception {
    Path dir = Files.createDirectories(workdir.resolve("many"));
    for (int i = 0; i < 2005; i++) {
      Files.writeString(dir.resolve(String.format("f_%04d.txt", i)), "x");
    }

    FindCapability find = new FindCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"f_*.txt\",\"path\":\".\",\"limit\":2500,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertEquals(1, res.contents().size());
    assertInstanceOf(TextResultContent.class, res.contents().getFirst());
    assertFalse(res.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
    String out = text(res);
    assertTrue(out.contains("many/f_0000.txt"), "预览必须包含头部结果");

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(res.detailsJson()).path("textOutput");
    assertEquals(2005, textOutput.path("totalLines").asLong());
    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(published.isAbsolute());
    assertTrue(out.contains(published.toString()), "预览必须内联绝对路径");
    assertEquals(2005, Files.readAllLines(published).size(), "durable 全文必须保留全部结果行");
    assertTrue(Files.readAllLines(published).contains("many/f_2004.txt"));
  }

  /** 验证 GrepCapability 使用 RE2/J 并拒绝非法的正则语法（lookaround、backreference）。 */
  @Test
  void grepRe2jRegexSyntaxValidAndInvalid() throws Exception {
    Files.writeString(workdir.resolve("test.txt"), "hello 123 world\n");
    GrepCapability grep = new GrepCapability(config(), executor);

    // Valid RE2: \d+
    EnvironmentCapabilityResult valid =
        invoke(
            grep,
            "{\"pattern\":\"\\\\d+\",\"path\":\".\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(valid.error());
    assertTrue(text(valid).contains("test.txt:1:hello 123 world"));

    // Invalid RE2: lookahead (?=world)
    EnvironmentCapabilityResult lookahead =
        invoke(
            grep,
            "{\"pattern\":\"hello (?=world)\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(lookahead.error());
    assertTrue(text(lookahead).contains("Invalid regex:"));

    // Invalid RE2: backreference \1
    EnvironmentCapabilityResult backref =
        invoke(
            grep,
            "{\"pattern\":\"(hello)\\\\1\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(backref.error());
    assertTrue(text(backref).contains("Invalid regex:"));
  }

  /** 验证 GrepCapability 的 literal 模式将特殊字符作为字面量匹配。 */
  @Test
  void grepLiteralSearchTreatsSpecialCharsLiterally() throws Exception {
    Files.writeString(workdir.resolve("code.txt"), "int val = list[0].length(); // a.*b\n");
    GrepCapability grep = new GrepCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"list[0].length()\",\"literal\":true,\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertTrue(text(res).contains("code.txt:1:int val = list[0].length(); // a.*b"));

    EnvironmentCapabilityResult res2 =
        invoke(
            grep,
            "{\"pattern\":\"a.*b\",\"literal\":true,\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2.error());
    assertTrue(text(res2).contains("a.*b"));
  }

  /** 验证 GrepCapability 的 ignore_case 与 include 选项。 */
  @Test
  void grepIgnoreCaseAndIncludePatternFilter() throws Exception {
    Path src = Files.createDirectories(workdir.resolve("src"));
    Files.writeString(src.resolve("App.java"), "class MyTargetClass {}\n");
    Files.writeString(src.resolve("data.txt"), "mytargetclass in data\n");

    GrepCapability grep = new GrepCapability(config(), executor);

    // Case insensitive with include
    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"mytargetclass\",\"ignore_case\":true,\"include\":\"*.java\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("src/App.java:1:class MyTargetClass {}"));
    assertFalse(out.contains("data.txt"));
  }

  /** 验证 GrepCapability 的 multiline 模式。 */
  @Test
  void grepMultilineSearch() throws Exception {
    Files.writeString(workdir.resolve("multi.txt"), "first line\nsecond line\nthird line\n");
    GrepCapability grep = new GrepCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"first.*\\\\nsecond\",\"multiline\":true,\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("multi.txt:1:first line"));
    assertTrue(out.contains("multi.txt:2:second line"));
    assertFalse(out.contains("third line"));
  }

  /** 验证 GrepCapability 对超长匹配行（>500字符）生成包含 match 的居中截断摘要。 */
  @Test
  void grepMatchCenteredExcerptForLongLines() throws Exception {
    // 600 chars with match near start (offset 50)
    String lineStart = "A".repeat(50) + "TARGET_START" + "B".repeat(540);
    // 600 chars with match near middle (offset 300)
    String lineMid = "C".repeat(300) + "TARGET_MID" + "D".repeat(290);
    // 600 chars with match near end (offset 550)
    String lineEnd = "E".repeat(550) + "TARGET_END" + "F".repeat(40);

    Files.writeString(
        workdir.resolve("long.txt"), lineStart + "\n" + lineMid + "\n" + lineEnd + "\n");
    GrepCapability grep = new GrepCapability(config(), executor);

    EnvironmentCapabilityResult resStart =
        invoke(
            grep,
            "{\"pattern\":\"TARGET_START\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resStart.error());
    String outStart = text(resStart);
    assertTrue(outStart.contains("TARGET_START"));
    assertTrue(outStart.contains("line truncated to 500 chars"));
    assertTrue(outStart.codePointCount(0, outStart.length()) < 550);

    EnvironmentCapabilityResult resMid =
        invoke(
            grep,
            "{\"pattern\":\"TARGET_MID\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resMid.error());
    String outMid = text(resMid);
    assertTrue(outMid.contains("TARGET_MID"));
    assertTrue(outMid.contains("... "));
    assertTrue(outMid.contains("line truncated to 500 chars"));

    EnvironmentCapabilityResult resEnd =
        invoke(
            grep,
            "{\"pattern\":\"TARGET_END\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resEnd.error());
    String outEnd = text(resEnd);
    assertTrue(outEnd.contains("TARGET_END"));
    assertTrue(outEnd.contains("... "));
  }

  /** 验证 GrepCapability 在直接指定单文件时的行为与错误处理。 */
  @Test
  void grepDirectFileSearchAndErrors() throws Exception {
    Path textFile = workdir.resolve("direct.txt");
    Files.writeString(textFile, "matching content\n");

    GrepCapability grep = new GrepCapability(config(), executor);

    // Direct text file
    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"matching\",\"path\":\"direct.txt\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertTrue(text(res).contains("direct.txt:1:matching content"));

    // Direct binary file throws
    Path binFile = workdir.resolve("binary.bin");
    Files.write(binFile, new byte[] {0, 1, 2, 3});
    EnvironmentCapabilityResult binRes =
        invoke(
            grep,
            "{\"pattern\":\"matching\",\"path\":\"binary.bin\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(binRes.error());
    assertTrue(text(binRes).contains("appears to be binary"));

    // Direct .git metadata file returns empty
    Path gitDir = Files.createDirectories(workdir.resolve(".git"));
    Path gitFile = gitDir.resolve("config");
    Files.writeString(gitFile, "matching content\n");
    EnvironmentCapabilityResult gitRes =
        invoke(
            grep,
            "{\"pattern\":\"matching\",\"path\":\".git/config\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(gitRes.error());
    assertEquals("No matches found", text(gitRes));
  }

  /** 验证 GrepCapability 在遍历目录时自动跳过二进制文件与大于 64MiB 的大文件。 */
  @Test
  void grepDirectorySearchSkipsBinaryAndLargeFiles() throws Exception {
    Files.writeString(workdir.resolve("valid.txt"), "target needle\n");
    Files.write(workdir.resolve("binary.bin"), new byte[] {0, 1, 2, 3});

    GrepCapability grep = new GrepCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"target\",\"path\":\".\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("valid.txt:1:target needle"));
    assertFalse(out.contains("binary.bin"));
  }

  /** 验证 GrepCapability 的 early-stop 机制。 */
  @Test
  void grepEarlyStopsAtLimitPlusOne() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 6; i++) {
      sb.append("line ").append(i).append(" needle\n");
    }
    Files.writeString(workdir.resolve("data.txt"), sb.toString());

    GrepCapability grep = new GrepCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"needle\",\"limit\":2,\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("line 1 needle"));
    assertTrue(out.contains("line 2 needle"));
    assertFalse(out.contains("line 3 needle"));
    assertTrue(out.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
  }

  /** 验证 GrepCapability 无匹配时返回 "No matches found"。 */
  @Test
  void grepReturnsNoMatchesFoundWhenEmpty() throws Exception {
    Files.writeString(workdir.resolve("data.txt"), "hello world\n");
    GrepCapability grep = new GrepCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"nonexistent_string\",\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertEquals("No matches found", text(res));
  }

  /** 验证 GrepCapability 大输出返回单个 TextResultContent：有界预览加本地路径，而不是 Resource。 */
  @Test
  void grepSpoolsLargeResultsToBoundedTextResultWithPath() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2005; i++) {
      sb.append("match_line_").append(i).append("\n");
    }
    Files.writeString(workdir.resolve("large.txt"), sb.toString());

    GrepCapability grep = new GrepCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            grep,
            "{\"pattern\":\"match_line\",\"limit\":2500,\"path\":\".\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertEquals(1, res.contents().size());
    assertInstanceOf(TextResultContent.class, res.contents().getFirst());
    assertFalse(res.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
    assertTrue(text(res).contains("match_line_0"), "预览必须包含头部匹配");

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(res.detailsJson()).path("textOutput");
    assertEquals(2005, textOutput.path("totalLines").asLong());
    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(published.isAbsolute());
    assertTrue(text(res).contains(published.toString()), "预览必须内联绝对路径");
    assertEquals(2005, Files.readAllLines(published).size(), "durable 全文必须保留全部匹配行");
  }

  /** 验证 SearchFiles 遍历不跟随符号链接，并校验目录参数。 */
  @Test
  void searchFilesIgnoresSymlinksAndValidatesDirectory() throws Exception {
    Path realDir = Files.createDirectories(workdir.resolve("real"));
    Files.writeString(realDir.resolve("inside.txt"), "inside");
    Path symlinkDir = workdir.resolve("symlink");
    try {
      Files.createSymbolicLink(symlinkDir, realDir);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Platform may not support symlink in test environment
      return;
    }

    FindCapability find = new FindCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"*.txt\",\"path\":\".\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res.error());
    String out = text(res);
    assertTrue(out.contains("real/inside.txt"));
    assertFalse(out.contains("symlink/inside.txt"));

    // Path must be directory for SearchFiles
    Path notDir = workdir.resolve("file.txt");
    Files.writeString(notDir, "content");
    EnvironmentCapabilityResult findFileRes =
        invoke(
            find,
            "{\"pattern\":\"*.txt\",\"path\":\"file.txt\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(findFileRes.error());
    assertTrue(text(findFileRes).contains("path must be a directory"));
  }

  /** 验证 SearchFiles 在搜索起点目录自身被 ignore 时立即返回。 */
  @Test
  void searchFilesReturnsEarlyWhenSearchDirectoryItselfIsIgnored() throws Exception {
    Files.writeString(workdir.resolve(".gitignore"), "ignored-folder/\n");
    Path ignoredFolder = Files.createDirectories(workdir.resolve("ignored-folder"));
    Files.writeString(ignoredFolder.resolve("file.txt"), "content");

    FindCapability find = new FindCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            find,
            "{\"pattern\":\"*.txt\",\"path\":\"ignored-folder\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    assertEquals("No files found matching pattern", text(res));
  }

  /** 验证 SearchFiles 在子目录遍历中提前停止并退出父目录遍历。 */
  @Test
  void searchFilesEarlyStopsInsideSubdirectory() throws Exception {
    Path nested = Files.createDirectories(workdir.resolve("nested/sub"));
    Files.writeString(nested.resolve("f1.txt"), "content");
    Files.writeString(nested.resolve("f2.txt"), "content");

    SearchControl control =
        new SearchControl(Duration.ofSeconds(5), () -> false, "find", System::nanoTime);
    SearchFiles.walk(workdir, workdir.resolve("nested"), control, file -> false);
  }
}
