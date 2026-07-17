package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class CodingToolsEdgeTest {

  @TempDir Path environmentRoot;

  @Test
  void everyDescriptorRejectsWrongTypedAndUnknownArguments() {
    Tool[] tools = {
      new WriteTool(config()),
      new EditTool(config()),
      new BashTool(config()),
      new GrepTool(config()),
      new FindTool(config())
    };
    String[] wrong = {
      "{\"path\":\"x\",\"content\":1}",
      "{\"path\":\"x\",\"old_string\":\"a\",\"new_string\":\"b\",\"replace_all\":\"yes\"}",
      "{\"command\":\"echo x\",\"workdir\":1}",
      "{\"pattern\":\"x\",\"path\":\".\",\"limit\":\"1\"}",
      "{\"pattern\":\"*\",\"path\":\".\",\"timeout_seconds\":false}"
    };
    for (int index = 0; index < tools.length; index++) {
      Tool tool = tools[index];
      String arguments = wrong[index];
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ToolExecutionRequest(
                  tool.descriptor(),
                  new ToolCall("invalid", tool.descriptor().name(), arguments),
                  Duration.ZERO));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ToolExecutionRequest(
                  tool.descriptor(),
                  new ToolCall("unknown", tool.descriptor().name(), "{\"unknown\":true}"),
                  Duration.ZERO));
    }
  }

  @Test
  void commonArgumentHelpersRejectInvalidValuesAndKeepDefaults() throws Exception {
    JsonNode values =
        AbstractCodingTool.OBJECT_MAPPER.readTree("{\"text\":\"x\",\"number\":2,\"truth\":true}");
    assertEquals("x", AbstractCodingTool.string(values, "text"));
    assertEquals(null, AbstractCodingTool.optionalString(values, "missing"));
    assertEquals(7, AbstractCodingTool.optionalPositiveInt(values, "missing", 7, 9));
    assertEquals(2, AbstractCodingTool.optionalPositiveInt(values, "number", 7, 9));
    assertTrue(AbstractCodingTool.optionalBoolean(values, "truth"));
    assertFalse(AbstractCodingTool.optionalBoolean(values, "missing"));
    assertThrows(IllegalArgumentException.class, () -> AbstractCodingTool.string(values, "number"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingTool.optionalPositiveInt(values, "text", 1, 9));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractCodingTool.optionalPositiveInt(
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"number\":0}"), "number", 1, 9));
    assertTrue(AbstractCodingTool.error("id", "failure").error());
    assertFalse(AbstractCodingTool.success("id", "ok").error());
  }

  @Test
  void pathBoundaryAcceptsCanonicalChildrenAndRejectsInvalidWorkdirs() throws Exception {
    Path nested = Files.createDirectories(environmentRoot.resolve("nested"));
    Files.writeString(nested.resolve("file.txt"), "x");
    EnvironmentPathBoundary boundary = new EnvironmentPathBoundary(config());

    assertEquals(nested.toRealPath(), boundary.workdir("@nested"));
    assertEquals(nested.resolve("file.txt").toRealPath(), boundary.existing("@file.txt", nested));
    assertEquals(nested.resolve("future/file.txt"), boundary.writable("future/file.txt", nested));
    assertEquals(environmentRoot.toRealPath(), boundary.workdir(null));
    assertThrows(IllegalArgumentException.class, () -> boundary.workdir("missing"));
    assertThrows(IllegalArgumentException.class, () -> boundary.workdir("file.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> boundary.workdir(environmentRoot.getParent().toString()));
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
  void outputLimiterHonorsExactThresholdsUtf8AndArtifactFailures() throws Exception {
    InMemoryArtifactSink sink = new InMemoryArtifactSink();
    CodingToolsConfig exact = config(2, 4, sink);
    List<ToolContent> untruncated =
        OutputLimiter.limit("ab\nc".getBytes(StandardCharsets.UTF_8), "text/plain", exact);
    List<ToolContent> truncated =
        OutputLimiter.limit(
            "😀x".getBytes(StandardCharsets.UTF_8), "text/plain", config(2, 4, sink));
    List<ToolContent> binary =
        OutputLimiter.limit(new byte[] {1, 0, 2}, "application/octet-stream", exact);

    assertEquals("ab\nc", text(untruncated));
    assertTrue(text(truncated).contains("Output truncated"));
    assertFalse(text(truncated).contains("�"));
    ArtifactToolContent artifact = (ArtifactToolContent) truncated.get(1);
    assertArrayEquals(
        "😀x".getBytes(StandardCharsets.UTF_8), sink.get(artifact.artifact().artifactId()));
    assertTrue(binary.get(1) instanceof ArtifactToolContent);
    CodingToolsConfig failing =
        config(
            1,
            1,
            (bytes, mediaType) -> {
              throw new IOException("sink down");
            });
    assertThrows(
        IOException.class,
        () -> OutputLimiter.limit("xx".getBytes(StandardCharsets.UTF_8), "text/plain", failing));
  }

  @Test
  void codecAndArtifactSinksPreserveAllSupportedBomFormats() throws Exception {
    byte[] utf16le = new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, '\n', 0};
    byte[] utf16be = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'a', 0, '\n'};
    TextFileCodec.Decoded little = TextFileCodec.decode(utf16le);
    TextFileCodec.Decoded big = TextFileCodec.decode(utf16be);
    assertEquals("a\n", little.text());
    assertArrayEquals(
        utf16le, TextFileCodec.encode(little.text(), little.charset(), little.bomLength()));
    assertArrayEquals(utf16be, TextFileCodec.encode(big.text(), big.charset(), big.bomLength()));
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(new byte[] {0, 1}));

    LocalFileArtifactSink local = new LocalFileArtifactSink(environmentRoot.resolve("artifacts"));
    var reference = local.store(new byte[] {7, 8}, "application/octet-stream");
    assertArrayEquals(
        new byte[] {7, 8}, Files.readAllBytes(local.directory().resolve(reference.artifactId())));
    InMemoryArtifactSink memory = new InMemoryArtifactSink();
    var memoryReference = memory.store(new byte[] {9}, "text/plain");
    byte[] copy = memory.get(memoryReference.artifactId());
    copy[0] = 0;
    assertArrayEquals(new byte[] {9}, memory.get(memoryReference.artifactId()));
  }

  @Test
  void readToolDecodesUtf16BomAsNumberedText() throws Exception {
    Files.write(
        environmentRoot.resolve("utf16.txt"),
        TextFileCodec.encode("alpha\nbeta\n", StandardCharsets.UTF_16LE, 2));

    ToolResult result = invoke(new ReadTool(config()), "{\"path\":\"utf16.txt\"}");

    assertFalse(result.error());
    assertTrue(text(result).contains("1|alpha"));
    assertTrue(text(result).contains("2|beta"));
    assertFalse(result.contents().stream().anyMatch(ArtifactToolContent.class::isInstance));
  }

  @Test
  void configSystemPropertiesAndValidationCoverStandaloneStartupInputs() throws Exception {
    String[] names = {
      "kkstudio.daemon.environment-root",
      "kkstudio.daemon.default-workdir",
      "kkstudio.daemon.artifact-directory",
      "kkstudio.daemon.bash",
      "kkstudio.daemon.rg",
      "kkstudio.daemon.fd"
    };
    String[] old = new String[names.length];
    for (int index = 0; index < names.length; index++) {
      old[index] = System.getProperty(names[index]);
    }
    try {
      System.setProperty(names[0], environmentRoot.toString());
      System.setProperty(names[1], environmentRoot.toString());
      System.setProperty(names[2], environmentRoot.resolve("local-artifacts").toString());
      System.setProperty(names[3], "custom-bash");
      System.setProperty(names[4], "custom-rg");
      System.setProperty(names[5], "custom-fd");
      CodingToolsConfig properties = CodingToolsConfig.fromSystemProperties();
      assertEquals(environmentRoot.toRealPath(), properties.environmentRoot());
      assertEquals("custom-bash", properties.bashExecutable());
      assertTrue(properties.artifactSink() instanceof LocalFileArtifactSink);
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
        () ->
            new CodingToolsConfig(
                environmentRoot,
                environmentRoot,
                0,
                1,
                "bash",
                "rg",
                "fd",
                new InMemoryArtifactSink()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CodingToolsConfig(
                environmentRoot,
                environmentRoot.resolve("missing"),
                1,
                1,
                "bash",
                "rg",
                "fd",
                new InMemoryArtifactSink()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CodingToolsConfig(
                environmentRoot,
                environmentRoot,
                1,
                1,
                "",
                "rg",
                "fd",
                new InMemoryArtifactSink()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CodingToolsConfig(
                environmentRoot.resolve("missing"),
                environmentRoot,
                1,
                1,
                "bash",
                "rg",
                "fd",
                new InMemoryArtifactSink()));
  }

  @Test
  void writeAndEditHandleCreateBinaryAndNoMatchErrors() throws Exception {
    WriteTool write = new WriteTool(config());
    EditTool edit = new EditTool(config());
    ToolResult created = invoke(write, "{\"path\":\"new/created.txt\",\"content\":\"one\"}");
    ToolResult unchanged =
        invoke(
            edit, "{\"path\":\"new/created.txt\",\"old_string\":\"one\",\"new_string\":\"one\"}");
    ToolResult absent =
        invoke(
            edit, "{\"path\":\"new/created.txt\",\"old_string\":\"zero\",\"new_string\":\"two\"}");
    Files.write(environmentRoot.resolve("binary.txt"), new byte[] {0, 1});
    ToolResult binary =
        invoke(edit, "{\"path\":\"binary.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
    ToolResult empty =
        invoke(edit, "{\"path\":\"new/created.txt\",\"old_string\":\"\",\"new_string\":\"b\"}");
    ToolResult directory =
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
  void scriptedSearchesCoverOptionsErrorsAndProcessCleanup() throws Exception {
    assumePosix();
    Path visible = environmentRoot.resolve("visible.txt");
    Files.writeString(visible, "content");
    String longLine = "x".repeat(600);
    Path rg =
        script("rg", "printf '%s\\n' '" + visible + ":1: " + longLine + "' 'file.txt:2: alpha'\n");
    Path fd = script("fd", "printf '%s\\n' './dir/a.txt' './dir/b.txt'\n");
    InMemoryArtifactSink sink = new InMemoryArtifactSink();
    GrepTool grep = new GrepTool(config(2000, 50 * 1024, sink, rg, fd));
    FindTool find = new FindTool(config(2000, 50 * 1024, sink, rg, fd));

    ToolResult literal =
        invoke(
            grep,
            "{\"pattern\":\"Alpha\",\"path\":\".\",\"literal\":true,\"ignore_case\":true,\"multiline\":true,\"include\":\"*.txt\",\"limit\":10}");
    assertTrue(text(literal).contains("visible.txt:1:"));
    assertFalse(text(literal).contains(environmentRoot.toString()));
    assertTrue(text(literal).contains("line truncated to 500 chars"));
    ArtifactToolContent grepArtifact =
        (ArtifactToolContent)
            literal.contents().stream()
                .filter(ArtifactToolContent.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertTrue(
        new String(sink.get(grepArtifact.artifact().artifactId()), StandardCharsets.UTF_8)
            .contains(longLine));

    ToolResult found = invoke(find, "{\"pattern\":\"dir/*.txt\",\"path\":\".\",\"limit\":1}");
    assertTrue(text(found).contains("dir/a.txt"));
    assertTrue(text(found).contains("results limit reached"));
    assertTrue(found.contents().stream().anyMatch(ArtifactToolContent.class::isInstance));

    Path argumentFile = environmentRoot.resolve("fd-arguments.txt");
    Path exactFd =
        script(
            "fd-exact",
            "printf '%s\\n' \"$@\" > '" + argumentFile + "'\nprintf '%s\\n' './dir/only.txt'\n");
    FindTool exactFind = new FindTool(config(2000, 50 * 1024, sink, rg, exactFd));
    ToolResult exact = invoke(exactFind, "{\"pattern\":\"dir/*.txt\",\"path\":\".\",\"limit\":1}");
    assertFalse(text(exact).contains("results limit reached"));
    assertTrue(Files.readString(argumentFile).contains("--full-path"));

    GrepTool missing =
        new GrepTool(
            config(
                2000,
                50 * 1024,
                new InMemoryArtifactSink(),
                environmentRoot.resolve("missing-rg"),
                fd));
    assertTrue(text(invoke(missing, "{\"pattern\":\"x\",\"path\":\".\"}")).contains("unavailable"));
    Path failing = script("failing", "echo bad >&2\nexit 2\n");
    GrepTool nonZero =
        new GrepTool(config(2000, 50 * 1024, new InMemoryArtifactSink(), failing, fd));
    assertTrue(text(invoke(nonZero, "{\"pattern\":\"x\",\"path\":\".\"}")).contains("bad"));
    FindTool missingFind =
        new FindTool(
            config(
                2000,
                50 * 1024,
                new InMemoryArtifactSink(),
                rg,
                environmentRoot.resolve("missing-fd")));
    assertTrue(
        text(invoke(missingFind, "{\"pattern\":\"*\",\"path\":\".\"}")).contains("unavailable"));
    FindTool failingFind =
        new FindTool(config(2000, 50 * 1024, new InMemoryArtifactSink(), rg, failing));
    assertTrue(text(invoke(failingFind, "{\"pattern\":\"*\",\"path\":\".\"}")).contains("bad"));
    assertTrue(
        text(invoke(find, "{\"pattern\":\"*\",\"path\":\"visible.txt\"}"))
            .contains("must be a directory"));
  }

  @Test
  void bashReportsStartupAndNonZeroFailuresWithoutDuplicateTerminalCallbacks() throws Exception {
    BashTool missing =
        new BashTool(
            new CodingToolsConfig(
                environmentRoot,
                environmentRoot,
                10,
                100,
                "missing-bash",
                "rg",
                "fd",
                new InMemoryArtifactSink()));
    assertTrue(text(invoke(missing, "{\"command\":\"echo x\"}")).contains("missing-bash"));
    BashTool bash = new BashTool(config());
    ToolResult nonZero = invoke(bash, "{\"command\":\"echo failure; exit 7\"}");
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
            new BashTool(config()),
            "{\"command\":\"printf '\\\\360\\\\237'; sleep 0.05; printf '\\\\230\\\\200'\"}",
            Duration.ofSeconds(2));

    assertTrue(listener.await());
    String partialText =
        listener.partials.stream().map(CodingToolsEdgeTest::text).reduce("", String::concat);
    assertEquals("😀", partialText);
    assertFalse(partialText.contains("�"));
    assertEquals(1, listener.completions);
  }

  @Test
  void scriptedSubprocessTimeoutAndCancellationReachOneTerminalResult() throws Exception {
    assumePosix();
    Path sleeper = script("sleep", "sleep 5\n");
    GrepTool grep =
        new GrepTool(config(2000, 50 * 1024, new InMemoryArtifactSink(), sleeper, sleeper));
    ToolResult timedOut = invoke(grep, "{\"pattern\":\"x\",\"path\":\".\",\"timeout_seconds\":1}");
    assertTrue(text(timedOut).contains("timed out"));

    RecordingListener listener =
        invokeAsync(
            grep, "{\"pattern\":\"x\",\"path\":\".\",\"timeout_seconds\":10}", Duration.ZERO);
    listener.handle.cancel();
    assertTrue(listener.await());
    assertEquals(1, listener.completions);
    assertTrue(text(listener.result).contains("Operation cancelled"));
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024, new InMemoryArtifactSink());
  }

  private CodingToolsConfig config(int lines, int bytes, ArtifactSink sink) {
    return config(lines, bytes, sink, Path.of("rg"), Path.of("fd"));
  }

  private CodingToolsConfig config(int lines, int bytes, ArtifactSink sink, Path rg, Path fd) {
    return new CodingToolsConfig(
        environmentRoot, environmentRoot, lines, bytes, "bash", rg.toString(), fd.toString(), sink);
  }

  private Path script(String name, String body) throws IOException {
    Path script = environmentRoot.resolve(name + "-script");
    Files.writeString(script, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(
        script,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    return script;
  }

  private static void assumePosix() {
    Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
  }

  private ToolResult invoke(Tool tool, String arguments) throws Exception {
    RecordingListener listener = invokeAsync(tool, arguments, Duration.ZERO);
    assertTrue(listener.await());
    return listener.result;
  }

  private RecordingListener invokeAsync(Tool tool, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("edge", tool.descriptor().name(), arguments),
                timeout),
            listener);
    return listener;
  }

  private static String text(ToolResult result) {
    return text(result.contents());
  }

  private static String text(List<ToolContent> contents) {
    return contents.stream().map(CodingToolsEdgeTest::text).reduce("", String::concat);
  }

  private static String text(ToolContent content) {
    return content instanceof TextToolContent value ? value.text() : "";
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<ToolResult> partials = new ArrayList<>();
    private volatile ToolResult result;
    private volatile ToolExecutionHandle handle;
    private volatile int completions;

    @Override
    public synchronized void onPartial(ToolResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(ToolResult result) {
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
