package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LargeTextResultSpooler} 的行为契约：只有单个 TextResultContent 的大文本被外化为本地全文，小输出、非文本内容、
 * 已外化结果与失败路径都保持原有语义。
 *
 * <p>被冻结的产品语义决定本测试重点：大文本终态必须给出存在的绝对路径与机器可读的 {@code textOutput} 事实， {@code error} 与已有 details
 * 键必须保留，本地存储失败时绝不给不存在的路径，也绝不把失败改写成成功。
 */
class LargeTextResultSpoolerTest {

  @TempDir Path root;

  private TextOutputStore store() {
    return TextOutputStore.open(root.resolve("tmp"));
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static EnvironmentCapabilityResult textResult(
      String text, boolean error, String details) {
    return new EnvironmentCapabilityResult(
        "call-1", List.of(new TextResultContent(text)), error, details);
  }

  /** 阈值之内的小输出必须原样返回：同一实例、contents/error/details 逐字不变，不产生任何本地文件。 */
  @Test
  void smallTextIsReturnedUnchangedVerbatim() throws IOException {
    TextOutputStore store = store();
    EnvironmentCapabilityResult original = textResult("small output\n", true, "{\"k\":\"v\"}");

    EnvironmentCapabilityResult result = LargeTextResultSpooler.spool(store, original);

    assertSame(original, result);
    assertTrue(result.error());
    assertEquals("small output\n", text(result));
    assertEquals("{\"k\":\"v\"}", result.detailsJson());
    assertTrue(store.publishedFiles().isEmpty());
    assertTrue(store.partialFiles().isEmpty());
  }

  /** 超过字节阈值：正文换成有界预览，全文发布为绝对路径文件，details 合并出 textOutput 事实。 */
  @Test
  void byteThresholdPublishesFullTextWithPreviewAndPath() throws IOException {
    TextOutputStore store = store();
    String payload = "0123456789".repeat(6000);
    assertTrue(
        payload.getBytes(StandardCharsets.UTF_8).length > OutputSpool.INLINE_MAX_BYTES,
        "夹具必须超过内联字节阈值");

    EnvironmentCapabilityResult result =
        LargeTextResultSpooler.spool(store, textResult(payload, false, "{}"));

    assertFalse(result.error());
    assertEquals(1, result.contents().size());
    assertTrue(result.contents().getFirst() instanceof TextResultContent);
    String preview = text(result);
    assertTrue(preview.contains(payload.substring(0, 10)), preview);
    assertTrue(preview.contains("Use read with offset/limit"), preview);

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(published.isAbsolute(), "必须给出绝对路径");
    assertTrue(published.toString().endsWith(".log"));
    assertEquals(payload, Files.readString(published), "durable 全文必须逐字完整");
    assertEquals(
        payload.getBytes(StandardCharsets.UTF_8).length, textOutput.path("totalBytes").asLong());
    assertFalse(textOutput.path("captureFailed").asBoolean());
    assertEquals(1, store.publishedFiles().size());
  }

  /** 字节很小但行数超过阈值同样落盘：不能只按字节判断。 */
  @Test
  void lineThresholdSpillsManyShortLines() throws IOException {
    TextOutputStore store = store();
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < OutputSpool.INLINE_MAX_LINES + 500; index++) {
      builder.append("x\n");
    }
    String payload = builder.toString();
    assertTrue(payload.getBytes(StandardCharsets.UTF_8).length < OutputSpool.INLINE_MAX_BYTES);

    EnvironmentCapabilityResult result =
        LargeTextResultSpooler.spool(store, textResult(payload, false, "{}"));

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
    assertEquals(OutputSpool.INLINE_MAX_LINES + 500, textOutput.path("totalLines").asLong());
    assertEquals(payload, Files.readString(Path.of(textOutput.path("path").asText())));
  }

  /** 多字节内容必须逐字节完整落盘，预览不得出现替换字符。 */
  @Test
  void unicodeContentIsPreservedExactly() throws IOException {
    TextOutputStore store = store();
    String payload = "🔥中文内容\n".repeat(8000);

    EnvironmentCapabilityResult result =
        LargeTextResultSpooler.spool(store, textResult(payload, false, "{}"));

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
    assertEquals(payload, Files.readString(Path.of(textOutput.path("path").asText())));
    assertFalse(text(result).contains("\uFFFD"), "预览不得切断多字节字符");
  }

  /** error 与已有 details 键必须保留：落盘后的 textOutput 只是合并，不覆盖其它键，也不把失败改写成成功。 */
  @Test
  void errorAndExistingDetailsArePreservedAndMerged() throws IOException {
    TextOutputStore store = store();
    String payload = "e".repeat(OutputSpool.INLINE_MAX_BYTES + 1);

    EnvironmentCapabilityResult result =
        LargeTextResultSpooler.spool(
            store, textResult(payload, true, "{\"code\":\"SKILL_SYNC_FAILED\"}"));

    assertTrue(result.error(), "error 必须保留");
    JsonNode details = AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson());
    assertEquals("SKILL_SYNC_FAILED", details.path("code").asText(), "已有 details 键不得被覆盖");
    assertTrue(details.path("textOutput").path("path").asText().endsWith(".log"));
  }

  /** JSON、二进制与多内容结果不参与外化：即使文本很大也原样返回。 */
  @Test
  void nonTextContentIsReturnedUnchanged() throws IOException {
    TextOutputStore store = store();
    String large = "j".repeat(OutputSpool.INLINE_MAX_BYTES + 1);

    EnvironmentCapabilityResult json =
        new EnvironmentCapabilityResult(
            "call-1",
            List.of(new JsonResultContent("{\"k\":\"" + "v".repeat(64) + "\"}")),
            false,
            "{}");
    EnvironmentCapabilityResult binary =
        new EnvironmentCapabilityResult(
            "call-1",
            List.of(new BinaryResultContent("image/png", new byte[] {1, 2, 3})),
            false,
            "{}");
    EnvironmentCapabilityResult multi =
        new EnvironmentCapabilityResult(
            "call-1",
            List.of(new TextResultContent(large), new TextResultContent("more")),
            false,
            "{}");

    assertSame(json, LargeTextResultSpooler.spool(store, json));
    assertSame(binary, LargeTextResultSpooler.spool(store, binary));
    assertSame(multi, LargeTextResultSpooler.spool(store, multi));
    assertTrue(store.publishedFiles().isEmpty());
  }

  /** 已由 OutputSpool 外化的结果（details 带 textOutput）不得二次落盘。 */
  @Test
  void alreadyExternalizedResultIsNotSpooledAgain() throws IOException {
    TextOutputStore store = store();
    // 构造一个「预览本身超过行阈值」的结果，证明跳过依据是 textOutput 标记而不是体积。
    String preview = "p\n".repeat(OutputSpool.INLINE_MAX_LINES + 100);
    EnvironmentCapabilityResult already =
        textResult(preview, false, "{\"textOutput\":{\"path\":\"/existing.log\"}}");

    assertSame(already, LargeTextResultSpooler.spool(store, already));
    assertTrue(store.publishedFiles().isEmpty(), "不得为已外化结果再落一份全文");
  }

  /** 本地存储不可用：明确说明全文无法保存、不给不存在的路径，并保留原有 error 标识。 */
  @Test
  void localStorageFailureKeepsErrorHonestWithoutPath() throws IOException {
    assumeTrue(supportsPosix(), "需要 POSIX 权限位来构造确定性的本地写入失败");
    TextOutputStore store = store();
    Files.setPosixFilePermissions(
        store.root(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

    EnvironmentCapabilityResult result =
        LargeTextResultSpooler.spool(
            store, textResult("z".repeat(OutputSpool.INLINE_MAX_BYTES + 1), true, "{}"));

    assertTrue(result.error(), "本地保存失败不得把失败改写成成功");
    String preview = text(result);
    assertTrue(preview.contains("could not be saved to local storage"), preview);
    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
    assertTrue(textOutput.path("captureFailed").asBoolean());
    assertTrue(textOutput.path("path").isMissingNode(), "失败时不得给出不存在的路径");
    assertTrue(store.publishedFiles().isEmpty());
  }

  /** 验证 AbstractCodingCapability 执行链路上的大文本外化契约：只有单个 TextResultContent 的大文本被外化，非文本/多内容原样保留。 */
  @Test
  void abstractCodingCapabilityExternalizesLargeTextResultsOnly() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      CodingToolsConfig config = TestCodingConfig.withLimits(root, 2000, 50 * 1024);
      String largeText = "x\n".repeat(OutputSpool.INLINE_MAX_LINES + 100);

      EnvironmentCapabilityResult textRes = textResult(largeText, false, "{}");
      EnvironmentCapabilityResult jsonRes =
          new EnvironmentCapabilityResult(
              "call-1",
              List.of(new JsonResultContent("{\"k\":\"" + "v".repeat(64) + "\"}")),
              false,
              "{}");
      EnvironmentCapabilityResult binaryRes =
          new EnvironmentCapabilityResult(
              "call-1",
              List.of(new BinaryResultContent("image/png", new byte[] {1, 2, 3})),
              false,
              "{}");
      EnvironmentCapabilityResult multiRes =
          new EnvironmentCapabilityResult(
              "call-1",
              List.of(new TextResultContent(largeText), new TextResultContent("more")),
              false,
              "{}");

      EnvironmentCapabilityResult spooledText = executeCapability(config, executor, textRes);
      EnvironmentCapabilityResult spooledJson = executeCapability(config, executor, jsonRes);
      EnvironmentCapabilityResult spooledBinary = executeCapability(config, executor, binaryRes);
      EnvironmentCapabilityResult spooledMulti = executeCapability(config, executor, multiRes);

      // 单个大文本被外化为绝对路径文件与 textOutput 结构
      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER
              .readTree(spooledText.detailsJson())
              .path("textOutput");
      assertTrue(textOutput.has("path"));
      assertTrue(Path.of(textOutput.path("path").asText()).isAbsolute());
      assertEquals(largeText, Files.readString(Path.of(textOutput.path("path").asText())));

      // JSON、二进制、多内容不被外化
      assertSame(jsonRes, spooledJson);
      assertSame(binaryRes, spooledBinary);
      assertSame(multiRes, spooledMulti);
    } finally {
      executor.shutdownNow();
    }
  }

  private static EnvironmentCapabilityResult executeCapability(
      CodingToolsConfig config,
      ExecutorService executor,
      EnvironmentCapabilityResult resultToReturn)
      throws Exception {
    EnvironmentCapability capability =
        new AbstractCodingCapability(
            config,
            executor,
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE)) {
          @Override
          EnvironmentCapabilityResult run(
              EnvironmentCapabilityExecutionRequest request, Execution execution) {
            return resultToReturn;
          }
        };

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EnvironmentCapabilityResult> ref = new AtomicReference<>();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall(
                "call-1", "{\"path\":\"/abs/test\",\"target\":\"/abs/Test.class\"}"),
            Duration.ofSeconds(10)),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            ref.set(result);
            latch.countDown();
          }

          @Override
          public void onError(Throwable error) {
            latch.countDown();
          }
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    return ref.get();
  }

  private static boolean supportsPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static List<Path> listFiles(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var entries = Files.list(directory)) {
      return entries.toList();
    }
  }
}
