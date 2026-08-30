package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CodingCapabilitiesEdgeTest {

  @TempDir Path environmentRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /** 错误类型与未知字段必须在执行前拒绝；integer 数字字符串会被归一化，因此 grep 用非数字文本证明类型失败。 */
  @Test
  void everyDescriptorRejectsWrongTypedAndUnknownArguments() {
    EnvironmentCapability[] capabilities = {
      write(config()), edit(config()), bash(config()), grep(config()), find(config())
    };
    String[] wrong = {
      "{\"path\":\"x\",\"content\":1}",
      "{\"path\":\"x\",\"old_string\":\"a\",\"new_string\":\"b\",\"replace_all\":\"yes\"}",
      "{\"command\":\"echo x\",\"workdir\":1}",
      "{\"pattern\":\"x\",\"path\":\".\",\"limit\":\"one\"}",
      "{\"pattern\":\"*\",\"path\":\".\",\"timeout_seconds\":false}"
    };
    for (int index = 0; index < capabilities.length; index++) {
      EnvironmentCapability capability = capabilities[index];
      String arguments = wrong[index];
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new EnvironmentCapabilityExecutionRequest(
                  capability.descriptor(),
                  new EnvironmentCapabilityCall("invalid", arguments),
                  Duration.ZERO,
                  environmentRoot));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new EnvironmentCapabilityExecutionRequest(
                  capability.descriptor(),
                  new EnvironmentCapabilityCall("unknown", "{\"unknown\":true}"),
                  Duration.ZERO,
                  environmentRoot));
    }
  }

  @Test
  void commonArgumentHelpersRejectInvalidValuesAndKeepDefaults() throws Exception {
    JsonNode values =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(
            "{\"text\":\"x\",\"number\":2,\"truth\":true}");
    assertEquals("x", AbstractCodingCapability.string(values, "text"));
    assertNull(AbstractCodingCapability.optionalString(values, "missing"));
    assertEquals(7, AbstractCodingCapability.optionalPositiveInt(values, "missing", 7, 9));
    assertEquals(2, AbstractCodingCapability.optionalPositiveInt(values, "number", 7, 9));
    assertEquals(2, AbstractCodingCapability.requiredPositiveInt(values, "number"));
    assertEquals(7, AbstractCodingCapability.optionalNonNegativeInt(values, "missing", 7));
    assertEquals(2, AbstractCodingCapability.optionalNonNegativeInt(values, "number", 7));
    assertTrue(AbstractCodingCapability.optionalBoolean(values, "truth"));
    assertFalse(AbstractCodingCapability.optionalBoolean(values, "missing"));
    assertThrows(
        IllegalArgumentException.class, () -> AbstractCodingCapability.string(values, "number"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.optionalPositiveInt(values, "text", 1, 9));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractCodingCapability.optionalPositiveInt(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"number\":0}"), "number", 1, 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.requiredPositiveInt(values, "missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.requiredPositiveInt(values, "text"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractCodingCapability.optionalNonNegativeInt(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"number\":-1}"), "number", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.optionalNonNegativeInt(values, "text", 1));
    assertTrue(AbstractCodingCapability.error("id", "failure").error());
    assertFalse(AbstractCodingCapability.success("id", "ok").error());
  }

  /** 共享 executor 上的 coding 任务收到 cancel 后必须被中断，并且只产生一次取消终态。 */
  @Test
  void abstractCodingExecutionUsesInjectedExecutorAndInterruptsOnCancel() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AbstractCodingCapability blocking =
        new AbstractCodingCapability(
            config(),
            executor,
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ)) {
          @Override
          EnvironmentCapabilityResult run(
              EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
            started.countDown();
            new CountDownLatch(1).await();
            return success(request.call().id(), "unexpected");
          }
        };
    RecordingListener listener = invokeAsync(blocking, "{\"path\":\"x\"}", Duration.ZERO);
    assertTrue(started.await(5, TimeUnit.SECONDS));

    listener.handle.cancel();
    listener.handle.cancel();

    assertTrue(listener.await());
    assertTrue(listener.result.error());
    assertTrue(text(listener.result).contains("Operation cancelled"));
    assertEquals(1, listener.completions);
    EnvironmentCapabilityExecutionRequest wrong =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall("wrong", "{\"command\":\"true\"}"),
            Duration.ofSeconds(1),
            environmentRoot);
    assertThrows(IllegalArgumentException.class, () -> blocking.execute(wrong, listener));
  }

  @Test
  void pathBoundaryAcceptsCanonicalChildrenAndRejectsInvalidWorkdirs() throws Exception {
    Path nested = Files.createDirectories(environmentRoot.resolve("nested"));
    Files.writeString(nested.resolve("file.txt"), "x");
    EnvironmentPathBoundary boundary = new EnvironmentPathBoundary(config());

    assertEquals(nested.toRealPath(), boundary.workdir("@nested", environmentRoot));
    assertEquals(nested.resolve("file.txt").toRealPath(), boundary.existing("@file.txt", nested));
    assertEquals(nested.resolve("future/file.txt"), boundary.writable("future/file.txt", nested));
    assertEquals(environmentRoot.toRealPath(), boundary.workdir(null, environmentRoot));
    assertThrows(
        IllegalArgumentException.class, () -> boundary.workdir("missing", environmentRoot));
    assertThrows(
        IllegalArgumentException.class, () -> boundary.workdir("file.txt", environmentRoot));
    assertThrows(
        IllegalArgumentException.class,
        () -> boundary.workdir(environmentRoot.getParent().toString(), environmentRoot));
    assertThrows(IllegalArgumentException.class, () -> boundary.existing("", nested));
    assertThrows(IllegalArgumentException.class, () -> boundary.existing("missing", nested));
    assertEquals(
        nested.resolve("file.txt").toRealPath(),
        boundary.existing(nested.resolve("file.txt").toString(), environmentRoot));
    assertEquals(nested.resolve("file.txt").toRealPath(), boundary.writable("file.txt", nested));
    assertThrows(NullPointerException.class, () -> boundary.existing("file.txt", null));
    assertThrows(IllegalArgumentException.class, () -> boundary.existing("\u0000", nested));
    Path dangling = environmentRoot.resolve("dangling");
    Files.createSymbolicLink(dangling, environmentRoot.resolve("not-created"));
    assertThrows(
        IllegalArgumentException.class, () -> boundary.existing("dangling", environmentRoot));
  }

  @Test
  void outputLimiterHonorsExactThresholdsUtf8AndResourceFailures() throws Exception {
    InMemoryResourceStore store = new InMemoryResourceStore();
    CodingToolsConfig exact = config(2, 4, store);
    EnvironmentCapabilityResult untruncated =
        OutputLimiter.limit("c1", "ab\nc".getBytes(StandardCharsets.UTF_8), "text/plain", exact);
    EnvironmentCapabilityResult truncated =
        OutputLimiter.limit(
            "c2", "😀x".getBytes(StandardCharsets.UTF_8), "text/plain", config(2, 4, store));
    EnvironmentCapabilityResult ansi =
        OutputLimiter.limit(
            "c3",
            "\u001b[31mpassed\u001b[0m".getBytes(StandardCharsets.UTF_8),
            "text/plain",
            config(2, 64, store));
    EnvironmentCapabilityResult binary =
        OutputLimiter.limit("c4", new byte[] {1, 0, 2}, "application/octet-stream", exact);

    assertEquals("ab\nc", text(untruncated.contents()));
    assertTrue(text(truncated.contents()).contains("Output truncated"));
    assertFalse(text(truncated).contains("�"));
    assertEquals("\u001b[31mpassed\u001b[0m", text(ansi.contents()));
    assertEquals(1, ansi.contents().size());
    ResourceResultContent resource = (ResourceResultContent) truncated.contents().get(1);
    assertArrayEquals(
        "😀x".getBytes(StandardCharsets.UTF_8), store.get(resource.resource().sha256()));
    assertTrue(binary.contents().get(1) instanceof ResourceResultContent);
    CodingToolsConfig failing =
        config(
            1,
            1,
            new ResourceStore() {
              @Override
              public DaemonResourceRef store(byte[] bytes, String mediaType) throws IOException {
                throw new IOException("store down");
              }

              @Override
              public byte[] read(DaemonResourceRef ref) throws IOException {
                throw new IOException("store down");
              }
            });
    assertThrows(
        IOException.class,
        () ->
            OutputLimiter.limit(
                "c5", "xx".getBytes(StandardCharsets.UTF_8), "text/plain", failing));
  }

  @Test
  void codecAndResourceStoresPreserveAllSupportedBomFormats() throws Exception {
    byte[] utf16le = new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, '\n', 0};
    byte[] utf16be = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'a', 0, '\n'};
    TextFileCodec.Decoded little = TextFileCodec.decode(utf16le);
    TextFileCodec.Decoded big = TextFileCodec.decode(utf16be);
    assertEquals("a\n", little.text());
    assertArrayEquals(
        utf16le, TextFileCodec.encode(little.text(), little.charset(), little.bomLength()));
    assertArrayEquals(utf16be, TextFileCodec.encode(big.text(), big.charset(), big.bomLength()));
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(new byte[] {0, 1}));

    LocalFileResourceStore local = new LocalFileResourceStore(environmentRoot.resolve("export"));
    var reference = local.store(new byte[] {7, 8}, "application/octet-stream");
    assertArrayEquals(
        new byte[] {7, 8}, Files.readAllBytes(local.directory().resolve(reference.sha256())));
    InMemoryResourceStore memory = new InMemoryResourceStore();
    var memoryReference = memory.store(new byte[] {9}, "text/plain");
    byte[] copy = memory.get(memoryReference.sha256());
    copy[0] = 0;
    assertArrayEquals(new byte[] {9}, memory.get(memoryReference.sha256()));
  }

  @Test
  void readToolDecodesUtf16BomAsNumberedText() throws Exception {
    Files.write(
        environmentRoot.resolve("utf16.txt"),
        TextFileCodec.encode("alpha\nbeta\n", StandardCharsets.UTF_16LE, 2));

    EnvironmentCapabilityResult result = invoke(read(config()), "{\"path\":\"utf16.txt\"}");

    assertFalse(result.error());
    assertTrue(text(result).contains("1|alpha"));
    assertTrue(text(result).contains("2|beta"));
    assertFalse(result.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
  }

  @Test
  void configSystemPropertiesAndValidationCoverStandaloneStartupInputs() throws Exception {
    String[] names = {
      "kkstudio.daemon.environment-root",
      "kkstudio.daemon.default-workdir",
      "kkstudio.daemon.resource-directory",
      "kkstudio.daemon.max-resource-bytes",
      "kkstudio.daemon.bash"
    };
    String[] old = new String[names.length];
    Path legacyRoot = Files.createDirectory(environmentRoot.resolve("legacy-root"));
    for (int index = 0; index < names.length; index++) {
      old[index] = System.getProperty(names[index]);
    }
    try {
      System.setProperty(names[0], legacyRoot.toString());
      System.setProperty(names[1], legacyRoot.toString());
      System.setProperty(names[2], environmentRoot.resolve("local-resources").toString());
      System.setProperty(names[3], "4");
      System.setProperty(names[4], "custom-bash");
      CodingToolsConfig properties = CodingToolsConfig.fromSystemProperties(environmentRoot);
      assertEquals(environmentRoot.toRealPath(), properties.environmentRoot());
      assertEquals("custom-bash", properties.bashExecutable());
      assertTrue(properties.resourceStore() instanceof LocalFileResourceStore);
      // 配置的 max-resource-bytes 必须落到 store：超限字节在写入前拒绝。
      assertThrows(
          IllegalArgumentException.class,
          () -> properties.resourceStore().store(new byte[] {1, 2, 3, 4, 5}, "text/plain"));
      System.setProperty(names[3], "0");
      assertThrows(
          IllegalArgumentException.class,
          () -> CodingToolsConfig.fromSystemProperties(environmentRoot));
      System.setProperty(names[3], "not-a-number");
      assertThrows(
          IllegalArgumentException.class,
          () -> CodingToolsConfig.fromSystemProperties(environmentRoot));
    } finally {
      for (int index = 0; index < names.length; index++) {
        if (old[index] == null) {
          System.clearProperty(names[index]);
        } else {
          System.setProperty(names[index], old[index]);
        }
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodingToolsConfig(environmentRoot, 0, 1, "bash", new InMemoryResourceStore()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodingToolsConfig(environmentRoot, 1, 1, "", new InMemoryResourceStore()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CodingToolsConfig(
                environmentRoot.resolve("missing"), 1, 1, "bash", new InMemoryResourceStore()));
    assertThrows(
        NullPointerException.class,
        () -> new CodingToolsConfig(environmentRoot, 1, 1, "bash", null));
  }

  @Test
  void writeAndEditHandleCreateBinaryAndNoMatchErrors() throws Exception {
    WriteCapability write = write(config());
    EditCapability edit = edit(config());
    EnvironmentCapabilityResult created =
        invoke(write, "{\"path\":\"new/created.txt\",\"content\":\"one\"}");
    EnvironmentCapabilityResult unchanged =
        invoke(
            edit, "{\"path\":\"new/created.txt\",\"old_string\":\"one\",\"new_string\":\"one\"}");
    EnvironmentCapabilityResult absent =
        invoke(
            edit, "{\"path\":\"new/created.txt\",\"old_string\":\"zero\",\"new_string\":\"two\"}");
    Files.write(environmentRoot.resolve("binary.txt"), new byte[] {0, 1});
    EnvironmentCapabilityResult binary =
        invoke(edit, "{\"path\":\"binary.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
    EnvironmentCapabilityResult empty =
        invoke(edit, "{\"path\":\"new/created.txt\",\"old_string\":\"\",\"new_string\":\"b\"}");
    EnvironmentCapabilityResult directory =
        invoke(edit, "{\"path\":\"new\",\"old_string\":\"a\",\"new_string\":\"b\"}");

    assertFalse(created.error());
    assertEquals("one", Files.readString(environmentRoot.resolve("new/created.txt")));
    assertTrue(text(unchanged).contains("must differ"));
    assertTrue(text(absent).contains("was not found"));
    assertTrue(text(binary).contains("appears to be binary"));
    assertTrue(text(empty).contains("must not be empty"));
    assertTrue(text(directory).contains("must be a file"));
  }

  @Test
  void bashReportsStartupAndNonZeroFailuresWithoutDuplicateTerminalCallbacks() throws Exception {
    BashCapability missing =
        bash(
            new CodingToolsConfig(
                environmentRoot, 10, 100, "missing-bash", new InMemoryResourceStore()));
    assertTrue(text(invoke(missing, "{\"command\":\"echo x\"}")).contains("missing-bash"));
    BashCapability bash = bash(config());
    EnvironmentCapabilityResult nonZero = invoke(bash, "{\"command\":\"echo failure; exit 7\"}");
    assertTrue(nonZero.error());
    assertTrue(text(nonZero).contains("Command exited with code 7"));
  }

  @Test
  void utf8StreamDecoderCarriesIncompleteSuffixAndFlushesMalformedTail() {
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8);

    assertEquals("", decoder.decode(emoji, 2));
    assertEquals("😀", decoder.decode(new byte[] {emoji[2], emoji[3]}, 2));
    assertEquals("", decoder.finish());
    assertEquals("", decoder.decode(new byte[] {(byte) 0xf0}, 1));
    assertEquals("�", decoder.finish());
    assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[1], 2));
  }

  @Test
  void bashStreamsSplitUtf8CodePointWithoutReplacementCharacters() throws Exception {
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"printf '\\\\360\\\\237'; sleep 0.05; printf '\\\\230\\\\200'\"}",
            Duration.ofSeconds(2));

    assertTrue(listener.await());
    String partialText =
        listener.partials.stream().map(CodingCapabilitiesEdgeTest::text).reduce("", String::concat);
    assertEquals("😀", partialText);
    assertFalse(partialText.contains("�"));
    assertEquals(1, listener.completions);
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024, new InMemoryResourceStore());
  }

  private ReadCapability read(CodingToolsConfig config) {
    return new ReadCapability(config, executor);
  }

  private WriteCapability write(CodingToolsConfig config) {
    return new WriteCapability(config, executor);
  }

  private EditCapability edit(CodingToolsConfig config) {
    return new EditCapability(config, executor);
  }

  private GrepCapability grep(CodingToolsConfig config) {
    return new GrepCapability(config, executor);
  }

  private FindCapability find(CodingToolsConfig config) {
    return new FindCapability(config, executor);
  }

  private BashCapability bash(CodingToolsConfig config) {
    return new BashCapability(config, executor, scheduler);
  }

  private CodingToolsConfig config(int lines, int bytes, ResourceStore store) {
    return new CodingToolsConfig(environmentRoot, lines, bytes, "bash", store);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, Duration.ZERO);
    assertTrue(listener.await());
    return listener.result;
  }

  private RecordingListener invokeAsync(
      EnvironmentCapability capability, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(),
                new EnvironmentCapabilityCall("edge", arguments),
                timeout,
                environmentRoot),
            listener);
    return listener;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return text(result.contents());
  }

  private static String text(List<ResultContent> contents) {
    return contents.stream().map(CodingCapabilitiesEdgeTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;
    private volatile int completions;

    @Override
    public synchronized void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completions++;
      done.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return done.await(5, TimeUnit.SECONDS);
    }
  }
}
