package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class NativeSearchCapabilitiesTest {

  @TempDir Path environmentRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  /** 根与嵌套 ignore 文件共同覆盖锚定、目录、double-star、转义、否定和父目录剪枝。 */
  @Test
  void respectsHierarchicalGitignoreRulesAndHardExcludesGitMetadata() throws Exception {
    write(
        ".gitignore",
        "\n"
            + "# comment\n"
            + "   \n"
            + "\\#literal.txt\n"
            + "\\!literal.txt\n"
            + "ignored-dir/\n"
            + "blocked/\n"
            + "nested/path-dir/\n"
            + "/root-only.txt\n"
            + "logs/**/debug?.log\n"
            + ".pi/git/*\n");
    write("visible.txt", "needle\n");
    write(".hidden.txt", "needle\n");
    write("#literal.txt", "needle\n");
    write("!literal.txt", "needle\n");
    write("root-only.txt", "needle\n");
    write("nested/root-only.txt", "needle\n");
    write("ignored-dir/file.txt", "needle\n");
    write("blocked/.gitignore", "!keep.txt\n");
    write("blocked/keep.txt", "needle\n");
    write("logs/debug1.log", "needle\n");
    write("logs/deep/debug2.log", "needle\n");
    write("logs/deep/keep.log", "needle\n");
    write("nested/.gitignore", "*.tmp\n!important.tmp\ncache/\n");
    write("nested/drop.tmp", "needle\n");
    write("nested/important.tmp", "needle\n");
    write("nested/cache/file.txt", "needle\n");
    write("nested/path-dir/file.txt", "needle\n");
    write(".pi/git/.gitignore", "!.gitignore\n");
    write(".pi/git/generated.txt", "needle\n");
    write(".git/config", "needle\n");

    EnvironmentCapabilityResult found =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    String findText = text(found);
    List<String> findLines = List.of(findText.split("\n"));
    assertTrue(findText.contains(".hidden.txt"));
    assertTrue(findText.contains(".pi/git/.gitignore"));
    assertTrue(findText.contains("nested/important.tmp"));
    assertTrue(findText.contains("nested/root-only.txt"));
    assertFalse(findText.contains("#literal.txt"));
    assertFalse(findText.contains("!literal.txt"));
    assertFalse(findLines.contains("root-only.txt"));
    assertFalse(findText.contains("ignored-dir/file.txt"));
    assertFalse(findText.contains("blocked/keep.txt"));
    assertFalse(findText.contains("debug1.log"));
    assertFalse(findText.contains("debug2.log"));
    assertFalse(findText.contains("nested/drop.tmp"));
    assertFalse(findText.contains("nested/cache/file.txt"));
    assertFalse(findText.contains("nested/path-dir/file.txt"));
    assertFalse(findText.contains(".pi/git/generated.txt"));
    assertFalse(findText.contains(".git/config"));

    EnvironmentCapabilityResult grepped =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    String grepText = text(grepped);
    assertTrue(grepText.contains(".hidden.txt:1:needle"));
    assertTrue(grepText.contains("logs/deep/keep.log:1:needle"));
    assertTrue(grepText.contains("nested/important.tmp:1:needle"));
    assertFalse(grepText.contains("ignored-dir"));
    assertFalse(grepText.contains("blocked"));
    assertFalse(grepText.contains("generated.txt"));
  }

  /** basename 否定只作用于当前节点，不能覆盖后代文件自己的 ignore 匹配。 */
  @Test
  void basenameNegationDoesNotReincludeIgnoredDescendantFiles() throws Exception {
    write(".gitignore", "*.log\n!foo\n");
    write("foo/bar.log", "ignored\n");
    write("foo/keep.txt", "visible\n");

    EnvironmentCapabilityResult negatedDirectory =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertTrue(text(negatedDirectory).contains("foo/keep.txt"));
    assertFalse(text(negatedDirectory).contains("foo/bar.log"));

    Files.writeString(environmentRoot.resolve(".gitignore"), "foo\n");
    EnvironmentCapabilityResult ignoredDirectory =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertFalse(text(ignoredDirectory).contains("foo/keep.txt"));
    assertFalse(text(ignoredDirectory).contains("foo/bar.log"));
  }

  /** find 必须区分 basename/full-path glob，只返回文件，并固定 workdir 相对顺序。 */
  @Test
  void findMatchesBasenamesAndSearchRelativePathsInDeterministicOrder() throws Exception {
    write("search/z.ts", "z");
    write("search/a.ts", "a");
    write("search/nested/b.ts", "b");
    write("search/nested/c.md", "c");
    write("search/.hidden.ts", "hidden");
    Files.createSymbolicLink(
        environmentRoot.resolve("search/link.ts"), environmentRoot.resolve("search/a.ts"));
    Files.createSymbolicLink(
        environmentRoot.resolve("search-link"), environmentRoot.resolve("search"));

    EnvironmentCapabilityResult basenames =
        invoke(
            find(config()),
            "{\"pattern\":\"*.ts\",\"path\":\"search\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts", text(basenames));
    assertFalse(text(basenames).contains("link.ts"));

    EnvironmentCapabilityResult fullPath =
        invoke(
            find(config()),
            "{\"pattern\":\"nested/*.ts\",\"path\":\"search\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals("search/nested/b.ts", text(fullPath));

    EnvironmentCapabilityResult doubleStar =
        invoke(
            find(config()),
            "{\"pattern\":\"**/*.ts\",\"path\":\"search\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts", text(doubleStar));

    EnvironmentCapabilityResult characterClass =
        invoke(
            find(config()),
            "{\"pattern\":\"[ab].ts\",\"path\":\"search\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals("search/a.ts\nsearch/nested/b.ts", text(characterClass));
    assertTrue(
        text(invoke(
                find(config()),
                "{\"pattern\":\"*\",\"path\":\"search/a.ts\",\"workdir\":"
                    + json(environmentRoot.toString())
                    + "}"))
            .contains("path must be a directory"));
    // 参数路径上的符号链接不再被拒绝：解析为真实路径后照常检索，遍历仍不跟随内部符号链接。
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts",
        text(
            invoke(
                find(config()),
                "{\"pattern\":\"*.ts\",\"path\":\"search-link\",\"workdir\":"
                    + json(environmentRoot.toString())
                    + "}")));
    assertEquals(
        "search/a.ts:1:a",
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"a\",\"path\":\"search/link.ts\",\"workdir\":"
                    + json(environmentRoot.toString())
                    + "}")));
  }

  /** grep 的 literal/regex/ignore-case/include 组合必须保持逐行去重。 */
  @Test
  void grepSupportsLiteralRegexIgnoreCaseAndIncludeWithoutDuplicateLines() throws Exception {
    write("a.txt", "Alpha foo\nalpha.foo alpha.foo\n");
    write("nested/c.txt", "ALPHA.FOO\n");
    write("b.md", "alpha.foo\n");

    EnvironmentCapabilityResult literal =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha.foo\",\"path\":\".\",\"literal\":true,"
                + "\"ignore_case\":true,\"include\":\"**/*.txt\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals("a.txt:2:alpha.foo alpha.foo\nnested/c.txt:1:ALPHA.FOO", text(literal));

    EnvironmentCapabilityResult regex =
        invoke(
            grep(config()),
            "{\"pattern\":\"^Alpha\\\\s+foo$\",\"path\":\"a.txt\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals("a.txt:1:Alpha foo", text(regex));

    EnvironmentCapabilityResult once =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha\\\\.foo\",\"path\":\"a.txt\",\"ignore_case\":true,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertEquals("a.txt:2:alpha.foo alpha.foo", text(once));
  }

  /** multiline 的两个跨行命中共享覆盖行时，该行仍只输出一次。 */
  @Test
  void grepMultilineReportsCoveredLinesOnce() throws Exception {
    write("multi.txt", "zero\nalpha\nbeta gamma\ndelta\n");

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha\\nbeta|gamma\\ndelta\",\"path\":\"multi.txt\","
                + "\"multiline\":true,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

    assertEquals("multi.txt:2:alpha\nmulti.txt:3:beta gamma\nmulti.txt:4:delta", text(result));
  }

  /** 直接二进制目标是清晰错误；目录扫描跳过二进制并继续返回文本匹配。 */
  @Test
  void grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex() throws Exception {
    write(".gitignore", "binary.bin\n");
    write("text.txt", "needle\n");
    Files.write(environmentRoot.resolve("binary.bin"), new byte[] {'n', 0, 'e'});

    EnvironmentCapabilityResult direct =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\"binary.bin\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertTrue(direct.error());
    assertTrue(text(direct).contains("file appears to be binary"));

    EnvironmentCapabilityResult directory =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertFalse(directory.error());
    assertEquals("text.txt:1:needle", text(directory));

    EnvironmentCapabilityResult invalid =
        invoke(
            grep(config()),
            "{\"pattern\":\"[\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertTrue(invalid.error());
    assertTrue(text(invalid).contains("Invalid regex"));
  }

  /** glob 转换覆盖 separator 边界、double-star、问号、字符类与转义字面量。 */
  @Test
  void globPatternsArePlatformIndependentAndSegmentAware() {
    assertTrue(GlobPattern.compile("**/*.java").matches("Main.java"));
    assertTrue(GlobPattern.compile("**/*.java").matches("src/main/Main.java"));
    assertTrue(GlobPattern.compile("a/**/b").matches("a/b"));
    assertTrue(GlobPattern.compile("a/**/b").matches("a/deep/nested/b"));
    assertTrue(GlobPattern.compile("foo/**").matches("foo/child"));
    assertTrue(GlobPattern.compile("foo/**").matches("foo/deep/child"));
    assertTrue(GlobPattern.compile("a/**/b?.[ch]").matches("a/deep/b1.c"));
    assertTrue(GlobPattern.compile("[!a].txt").matches("b.txt"));
    assertTrue(GlobPattern.compile("[]].txt").matches("].txt"));
    assertTrue(GlobPattern.compile("\\*.txt").matches("*.txt"));
    assertTrue(GlobPattern.compile("[.txt").matches("[.txt"));
    assertTrue(GlobPattern.compile("[[]").matches("["));
    assertTrue(GlobPattern.compile("[a\\]]").matches("]"));
    assertTrue(GlobPattern.compile("[\\d].txt").matches("d.txt"));
    assertTrue(GlobPattern.compile("a**b").matches("ab"));
    assertTrue(GlobPattern.compile("a**b").matches("axxb"));
    assertTrue(GlobPattern.compile("**.java").matches("Main.java"));
    assertTrue(GlobPattern.compile("foo**bar").matches("foo-middle-bar"));
    assertTrue(GlobPattern.compile("abc\\").matches("abc\\"));
    assertFalse(GlobPattern.compile("*.java").matches("src/Main.java"));
    assertFalse(GlobPattern.compile("a/**/b?.[ch]").matches("a/deep/b12.c"));
    assertFalse(GlobPattern.compile("[!a].txt").matches("a.txt"));
    assertFalse(GlobPattern.compile("[\\d].txt").matches("1.txt"));
    assertFalse(GlobPattern.compile("a**b").matches("ax/yb"));
    assertFalse(GlobPattern.compile("**.java").matches("src/Main.java"));
    assertFalse(GlobPattern.compile("foo**bar").matches("foo/deep/bar"));
  }

  /** 验证 limit 与 500 code-point 行截断保持有界内联文本，不附加无意义完整 resource。 */
  @Test
  void searchLimitsStayInlineAndTruncateLongLines() throws Exception {
    String longLine = "😀".repeat(600);
    write("a.txt", longLine + "\n");
    write("b.txt", "needle\n");
    write("c.txt", "needle\n");
    GrepCapability grep = grep(config(2000, 50 * 1024));

    EnvironmentCapabilityResult grepResult =
        invoke(
            grep,
            "{\"pattern\":\".+\",\"path\":\".\",\"include\":\"*.txt\",\"limit\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertTrue(text(grepResult).contains("line truncated to 500 chars"));
    assertTrue(text(grepResult).contains("1 results limit reached"));
    assertFalse(grepResult.contents().stream().anyMatch(ResourceResultContent.class::isInstance));

    EnvironmentCapabilityResult findResult =
        invoke(
            find(config(2000, 50 * 1024)),
            "{\"pattern\":\"*.txt\",\"path\":\".\",\"limit\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    assertTrue(text(findResult).contains("1 results limit reached"));
    assertFalse(findResult.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
  }

  /** 搜索控制器使用可控时钟验证 deadline，并用 cancellation supplier 验证主动取消检查。 */
  @Test
  void searchControlActivelyChecksTimeoutAndCancellation() {
    AtomicLong clock = new AtomicLong();
    SearchControl timeout = new SearchControl(Duration.ofNanos(5), () -> false, "grep", clock::get);
    clock.set(5);
    IllegalArgumentException timedOut =
        assertThrows(IllegalArgumentException.class, timeout::check);
    assertEquals("grep timed out after 0 milliseconds", timedOut.getMessage());

    SearchControl cancelled =
        new SearchControl(Duration.ofSeconds(1), () -> true, "find", System::nanoTime);
    assertThrows(InterruptedException.class, cancelled::check);
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024);
  }

  private GrepCapability grep(CodingToolsConfig config) {
    return new GrepCapability(config, executor);
  }

  private FindCapability find(CodingToolsConfig config) {
    return new FindCapability(config, executor);
  }

  private CodingToolsConfig config(int lines, int bytes) {
    return TestCodingConfig.withLimits(environmentRoot, lines, bytes);
  }

  private void write(String relative, String content) throws Exception {
    Path path = environmentRoot.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("native-search", arguments),
            Duration.ZERO),
        listener);
    assertTrue(listener.completed.await(5, TimeUnit.SECONDS));
    return listener.result;
  }

  private static ResourceResultContent resource(EnvironmentCapabilityResult result) {
    return (ResourceResultContent)
        result.contents().stream()
            .filter(ResourceResultContent.class::isInstance)
            .findFirst()
            .orElseThrow();
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .map(NativeSearchCapabilitiesTest::text)
        .reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {}

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }
  }
}
