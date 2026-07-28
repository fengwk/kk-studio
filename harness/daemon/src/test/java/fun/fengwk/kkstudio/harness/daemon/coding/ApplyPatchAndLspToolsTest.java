package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Covers apply_patch registration/protocol atomicity and LSP unavailable/javap fallback contracts.
 */
class ApplyPatchAndLspToolsTest {

  @TempDir Path environmentRoot;

  @Test
  void registersNewToolsWithStableSchemasAndPromptAssets() {
    DaemonToolRegistry registry = new DaemonToolRegistry();
    CodingTools.registerAll(registry, config());
    assertEquals(
        List.of(
            "read",
            "write",
            "edit",
            "apply_patch",
            "bash",
            "grep",
            "find",
            "lsp_goto_definition",
            "lsp_workspace_symbols",
            "lsp_java_decompile"),
        registry.descriptors().stream().map(ToolDescriptor::name).toList());

    Tool apply = registry.find("apply_patch").orElseThrow();
    assertTrue(apply.descriptor().description().contains("*** Begin Patch"));
    assertEquals(Set.of("patchText"), apply.descriptor().inputSchema().required());
    assertEquals(Set.of("patchText"), apply.descriptor().inputSchema().properties().keySet());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                apply.descriptor(),
                new ToolCall("bad", "apply_patch", "{\"patchText\":\"x\",\"workdir\":\".\"}"),
                Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                apply.descriptor(),
                new ToolCall("bad-type", "apply_patch", "{\"patchText\":1}"),
                Duration.ZERO));

    Tool bash = registry.find("bash").orElseThrow();
    assertEquals(Set.of("command"), bash.descriptor().inputSchema().required());
    assertTrue(bash.descriptor().inputSchema().properties().containsKey("timeout_seconds"));
    assertEquals(Duration.ofHours(1), bash.descriptor().timeout());

    Tool gotoDef = registry.find("lsp_goto_definition").orElseThrow();
    assertEquals(Set.of("path", "line"), gotoDef.descriptor().inputSchema().required());
    assertTrue(gotoDef.descriptor().inputSchema().properties().containsKey("character"));

    Tool symbols = registry.find("lsp_workspace_symbols").orElseThrow();
    assertEquals(Set.of("path", "query"), symbols.descriptor().inputSchema().required());
    assertTrue(symbols.descriptor().inputSchema().properties().containsKey("limit"));

    Tool decompile = registry.find("lsp_java_decompile").orElseThrow();
    assertEquals(Set.of("path", "target"), decompile.descriptor().inputSchema().required());
    assertTrue(CodingToolPrompts.load("lsp_java_decompile").contains("jdtls"));
  }

  @Test
  void applyPatchAddsUpdatesDeletesWithPreflightAtomicityAndMoves() throws Exception {
    Files.writeString(environmentRoot.resolve("keep.txt"), "alpha\nbeta\n");
    Files.writeString(environmentRoot.resolve("gone.txt"), "remove-me\n");
    ApplyPatchTool tool = new ApplyPatchTool(config());

    ToolResult success =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Add File: nested/new.txt
                +hello
                +world
                *** Update File: keep.txt
                @@
                 alpha
                -beta
                +gamma
                *** Delete File: gone.txt
                *** End Patch
                """));
    assertFalse(success.error(), text(success));
    assertEquals("hello\nworld\n", Files.readString(environmentRoot.resolve("nested/new.txt")));
    assertEquals("alpha\ngamma\n", Files.readString(environmentRoot.resolve("keep.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("gone.txt")));
    assertTrue(text(success).contains("A 1 U 1 D 1"));

    // Preflight failure must not mutate any file in the same patch.
    Files.writeString(environmentRoot.resolve("keep.txt"), "alpha\ngamma\n");
    ToolResult preflight =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Add File: nested/new.txt
                +should-not-create
                *** Update File: keep.txt
                @@
                -gamma
                +delta
                *** End Patch
                """));
    assertTrue(preflight.error());
    assertTrue(text(preflight).contains("preflight"));
    assertEquals("hello\nworld\n", Files.readString(environmentRoot.resolve("nested/new.txt")));
    assertEquals("alpha\ngamma\n", Files.readString(environmentRoot.resolve("keep.txt")));

    ToolResult moved =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: keep.txt
                *** Move to: moved.txt
                @@
                -gamma
                +delta
                *** End Patch
                """));
    assertFalse(moved.error(), text(moved));
    assertEquals("alpha\ndelta\n", Files.readString(environmentRoot.resolve("moved.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("keep.txt")));

    ToolResult renamed =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: moved.txt
                *** Move to: renamed.txt
                *** End Patch
                """));
    assertFalse(renamed.error(), text(renamed));
    assertEquals("alpha\ndelta\n", Files.readString(environmentRoot.resolve("renamed.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("moved.txt")));

    Files.writeString(environmentRoot.resolve("destination.txt"), "old destination\n");
    ToolResult overwritten =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: renamed.txt
                *** Move to: destination.txt
                *** End Patch
                """));
    assertFalse(overwritten.error(), text(overwritten));
    assertEquals("alpha\ndelta\n", Files.readString(environmentRoot.resolve("destination.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("renamed.txt")));

    byte[] bomSource =
        new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'x', '\r', '\n', 'y', '\r', '\n'};
    Files.write(environmentRoot.resolve("bom-source.txt"), bomSource);
    ToolResult bomMoved =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: bom-source.txt
                *** Move to: bom-destination.txt
                *** End Patch
                """));
    assertFalse(bomMoved.error(), text(bomMoved));
    assertArrayEquals(
        bomSource, Files.readAllBytes(environmentRoot.resolve("bom-destination.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("bom-source.txt")));
  }

  @Test
  void applyPatchRejectsAmbiguousExactMatchesAndPreservesBomEncoding() throws Exception {
    Path bomFile = environmentRoot.resolve("bom.txt");
    Files.write(
        bomFile,
        new byte[] {
          (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'x', '\n', 'y', '\n', 'x', '\n', 'z', '\n'
        });
    ApplyPatchTool tool = new ApplyPatchTool(config());
    ToolResult ambiguous =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: bom.txt
                @@
                -x
                +X
                *** End Patch
                """));
    assertTrue(ambiguous.error());
    assertTrue(
        text(ambiguous).contains("ambiguous") || text(ambiguous).contains("could not match"));

    ToolResult updated =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: bom.txt
                @@
                 y
                -x
                +X
                 z
                *** End Patch
                """));
    assertFalse(updated.error(), text(updated));
    byte[] bytes = Files.readAllBytes(bomFile);
    assertEquals((byte) 0xef, bytes[0]);
    assertEquals((byte) 0xbb, bytes[1]);
    assertEquals((byte) 0xbf, bytes[2]);
    assertEquals("x\ny\nX\nz\n", new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
  }

  @Test
  void applyPatchRejectsMalformedProtocolAndPathEscape() throws Exception {
    ApplyPatchTool tool = new ApplyPatchTool(config());
    ToolResult missingMarkers = invoke(tool, patchArgs("not a patch"));
    assertTrue(missingMarkers.error());
    assertTrue(text(missingMarkers).contains("Begin Patch"));

    ToolResult escape =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Add File: ../outside.txt
                +nope
                *** End Patch
                """));
    assertTrue(escape.error());
    assertTrue(text(escape).contains("environment root") || text(escape).contains("escapes"));
  }

  @Test
  void applyPatchUsesHeaderWorkdirAndRejectsInvalidMoveTargetsWithoutMutation() throws Exception {
    Path nested = Files.createDirectories(environmentRoot.resolve("nested-workdir"));
    Files.writeString(nested.resolve("source.txt"), "source\n");
    ApplyPatchTool tool = new ApplyPatchTool(config());

    ToolResult workdir =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Workdir: nested-workdir
                *** Update File: source.txt
                @@
                -source
                +updated
                *** End Patch
                """));
    assertFalse(workdir.error(), text(workdir));
    assertEquals("updated\n", Files.readString(nested.resolve("source.txt")));

    Files.writeString(nested.resolve("source.txt"), "source\n");
    ToolResult escapedSource =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Workdir: nested-workdir
                *** Add File: should-not-create.txt
                +no
                *** Update File: ../../outside.txt
                *** Move to: destination.txt
                *** End Patch
                """));
    assertTrue(escapedSource.error());
    assertFalse(Files.exists(nested.resolve("should-not-create.txt")));
    assertEquals("source\n", Files.readString(nested.resolve("source.txt")));

    ToolResult escapedDestination =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Workdir: nested-workdir
                *** Update File: source.txt
                *** Move to: ../../outside.txt
                *** End Patch
                """));
    assertTrue(escapedDestination.error());
    assertTrue(text(escapedDestination).contains("environment root"));
    assertEquals("source\n", Files.readString(nested.resolve("source.txt")));

    Files.createDirectory(nested.resolve("directory"));
    ToolResult directoryDestination =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Workdir: nested-workdir
                *** Update File: source.txt
                *** Move to: directory
                *** End Patch
                """));
    assertTrue(directoryDestination.error());
    assertTrue(text(directoryDestination).contains("not a regular file"));
    assertEquals("source\n", Files.readString(nested.resolve("source.txt")));
  }

  @Test
  void applyPatchRejectsDuplicateAndMisplacedWorkdirHeaders() {
    assertEquals(
        "nested",
        ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Workdir: nested
                *** Add File: file.txt
                +ok
                *** End Patch
                """)
            .workdir());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Workdir:
                *** Add File: file.txt
                +ok
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Workdir: first
                *** Workdir: second
                *** Add File: file.txt
                +ok
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Add File: file.txt
                +ok
                *** Workdir: late
                *** End Patch
                """));
  }

  @Test
  void lspToolsReturnDeterministicUnavailableWithoutBridge() throws Exception {
    Files.writeString(environmentRoot.resolve("Main.java"), "class Main {}");
    LspGotoDefinitionTool gotoDef = new LspGotoDefinitionTool(config());
    LspWorkspaceSymbolsTool symbols = new LspWorkspaceSymbolsTool(config());

    ToolResult gotoResult = invoke(gotoDef, "{\"path\":\"Main.java\",\"line\":1,\"character\":0}");
    ToolResult symbolsResult =
        invoke(symbols, "{\"path\":\"Main.java\",\"query\":\"Main\",\"limit\":10}");

    assertTrue(gotoResult.error());
    assertTrue(text(gotoResult).contains("LSP bridge is unavailable"));
    assertTrue(symbolsResult.error());
    assertTrue(text(symbolsResult).contains("LSP bridge is unavailable"));
  }

  @Test
  void lspBridgeSuccessAndArgumentValidation() throws Exception {
    Path script = environmentRoot.resolve("bridge.sh");
    Files.writeString(
        script,
        """
        #!/bin/sh
        cat >/dev/null
        printf '%s' '{"ok":true,"text":"bridge-hit"}'
        """);
    Files.setPosixFilePermissions(
        script,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    Files.writeString(environmentRoot.resolve("Main.java"), "class Main {}");
    CodingToolsConfig bridged =
        new CodingToolsConfig(
            environmentRoot,
            environmentRoot,
            2000,
            50 * 1024,
            "bash",
            "rg",
            "fd",
            new InMemoryArtifactSink(),
            script.toString(),
            System.getProperty("java.home") + "/bin/javap");
    LspGotoDefinitionTool gotoDef = new LspGotoDefinitionTool(bridged);
    ToolResult ok = invoke(gotoDef, "{\"path\":\"Main.java\",\"line\":1}");
    assertFalse(ok.error(), text(ok));
    assertEquals("bridge-hit", text(ok));

    ToolResult invalidLine = invoke(gotoDef, "{\"path\":\"Main.java\",\"line\":0}");
    assertTrue(invalidLine.error());
    assertTrue(text(invalidLine).contains("positive integer"));

    LspWorkspaceSymbolsTool symbols = new LspWorkspaceSymbolsTool(bridged);
    ToolResult blankQuery = invoke(symbols, "{\"path\":\"Main.java\",\"query\":\"  \"}");
    assertTrue(blankQuery.error());
    assertTrue(text(blankQuery).contains("query must not be blank"));
  }

  @Test
  void javaDecompileUsesJavapFallbackAndValidatesTarget() throws Exception {
    Files.writeString(environmentRoot.resolve("Main.java"), "class Main {}");
    String javap = System.getProperty("java.home") + "/bin/javap";
    CodingToolsConfig config =
        new CodingToolsConfig(
            environmentRoot,
            environmentRoot,
            2000,
            50 * 1024,
            "bash",
            "rg",
            "fd",
            new InMemoryArtifactSink(),
            null,
            javap);
    LspJavaDecompileTool tool = new LspJavaDecompileTool(config);

    ToolResult blank = invoke(tool, "{\"path\":\"Main.java\",\"target\":\"   \"}");
    assertTrue(blank.error());
    assertTrue(text(blank).contains("target must not be blank"));

    ToolResult unresolvable =
        invoke(tool, "{\"path\":\"Main.java\",\"target\":\"not-a-resolvable-target###\"}");
    assertTrue(unresolvable.error());
    assertTrue(
        text(unresolvable).contains("unavailable")
            || text(unresolvable).contains("could not resolve"));

    ToolResult jdt =
        invoke(
            tool,
            "{\"path\":\"Main.java\",\"target\":\"jdt://contents/java.base/java/lang/String.class?query=1\"}");
    assertFalse(jdt.error(), text(jdt));
    String jdtText = text(jdt);
    assertTrue(
        jdtText.contains("String")
            && (jdtText.contains("Classfile") || jdtText.contains("Compiled")),
        jdtText);
    assertTrue(jdtText.contains("javap fallback"));

    ToolResult simple =
        invoke(
            tool,
            "{\"path\":\"Main.java\",\"target\":\"String (Class) - jdt://contents/java.base/java/lang/String.class\"}");
    assertFalse(simple.error(), text(simple));
    assertTrue(text(simple).toLowerCase(Locale.ROOT).contains("string"));
  }

  @Test
  void applyPatchRejectsStaleMoveDestinationAfterPreflight() throws Exception {
    Path source = environmentRoot.resolve("source.txt");
    Path destination = environmentRoot.resolve("destination.txt");
    Files.writeString(source, "source\n");
    Files.writeString(destination, "destination\n");
    Path marker = pathWithDifferentStripe("stale-marker", destination);
    ApplyPatchTool tool = new ApplyPatchTool(config());
    var destinationLock = FileMutations.lock(destination);
    RecordingListener listener;
    try {
      listener =
          invokeAsync(
              tool,
              patchArgs(
                  """
                  *** Begin Patch
                  *** Add File: %s
                  +committed-before-move
                  *** Update File: source.txt
                  *** Move to: destination.txt
                  *** End Patch
                  """
                      .formatted(marker.getFileName())));
      assertTrue(waitForExists(marker));
      Files.writeString(destination, "racer\n");
    } finally {
      destinationLock.unlock();
    }

    assertTrue(listener.await());
    assertTrue(listener.result.error());
    assertTrue(text(listener.result).contains("destination.txt changed after preflight"));
    assertEquals("source\n", Files.readString(source));
    assertEquals("racer\n", Files.readString(destination));
    assertEquals("committed-before-move\n", Files.readString(marker));
  }

  @Test
  void oppositeMovesAcquireSourceAndDestinationLocksWithoutDeadlock() throws Exception {
    Path sourceA = environmentRoot.resolve("a.txt");
    Path sourceB = environmentRoot.resolve("b.txt");
    Files.writeString(sourceA, "a\n");
    Files.writeString(sourceB, "b\n");
    Path markerA = pathWithDifferentStripe("move-marker-a", sourceA, sourceB);
    Path markerB = pathWithDifferentStripe("move-marker-b", sourceA, sourceB, markerA);
    ApplyPatchTool tool = new ApplyPatchTool(config());
    var heldLocks = FileMutations.lockAll(sourceA, sourceB);
    RecordingListener first;
    RecordingListener second;
    try {
      first =
          invokeAsync(
              tool,
              patchArgs(
                  """
                  *** Begin Patch
                  *** Add File: %s
                  +first-preflight-complete
                  *** Update File: a.txt
                  *** Move to: b.txt
                  *** End Patch
                  """
                      .formatted(markerA.getFileName())));
      second =
          invokeAsync(
              tool,
              patchArgs(
                  """
                  *** Begin Patch
                  *** Add File: %s
                  +second-preflight-complete
                  *** Update File: b.txt
                  *** Move to: a.txt
                  *** End Patch
                  """
                      .formatted(markerB.getFileName())));
      assertTrue(waitForExists(markerA));
      assertTrue(waitForExists(markerB));
    } finally {
      FileMutations.unlockAll(heldLocks);
    }

    assertTrue(first.await());
    assertTrue(second.await());
    assertTrue(first.result.error() ^ second.result.error());
    assertEquals(1, (Files.exists(sourceA) ? 1 : 0) + (Files.exists(sourceB) ? 1 : 0));
  }

  @Test
  void parserExposesDuplicateAndEmptyUpdateErrors() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Add File: a.txt
                +1
                *** Add File: a.txt
                +2
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Update File: a.txt
                *** End Patch
                """));
  }

  /** Parser 必须在提交前拒绝每种结构歧义，避免协议文本被静默解释成其他文件操作。 */
  @Test
  void parserRejectsMalformedBodiesAndTerminalStructure() {
    assertThrows(IllegalArgumentException.class, () -> ApplyPatchSupport.parse(" \n"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Delete File: a.txt
                +unexpected body
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Update File: a.txt
                not a chunk
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Update File: a.txt
                @@
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Update File: a.txt
                @@
                -old
                +new
                *** End of File
                 trailing body
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Update File: a.txt
                @@
                !invalid line
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                unknown directive
                *** End Patch
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Add File: a.txt
                +content
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** Add File: a.txt
                +content
                *** End Patch
                trailing text
                """));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApplyPatchSupport.parse(
                """
                *** Begin Patch
                *** End Patch
                """));
    assertEquals(
        1,
        ApplyPatchSupport.parse(
                """
                  *** Begin Patch
                *** Add File: padded.txt
                +content
                \t*** End Patch
                """)
            .files()
            .size());
  }

  @Test
  void applyPatchRejectsHierarchicalOutputConflictsBeforeMutation() throws Exception {
    ApplyPatchTool tool = new ApplyPatchTool(config());

    ToolResult result =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Add File: generated
                +file
                *** Add File: generated/child.txt
                +child
                *** End Patch
                """));

    assertTrue(result.error());
    assertTrue(text(result).contains("Conflicting patch output paths"));
    assertFalse(Files.exists(environmentRoot.resolve("generated")));
  }

  @Test
  void applyPatchRejectsNonMutatingUpdates() throws Exception {
    Files.writeString(environmentRoot.resolve("unchanged.txt"), "same\n");
    ApplyPatchTool tool = new ApplyPatchTool(config());

    ToolResult contextOnly =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: unchanged.txt
                @@
                 same
                *** End Patch
                """));
    ToolResult semanticNoOp =
        invoke(
            tool,
            patchArgs(
                """
                *** Begin Patch
                *** Update File: unchanged.txt
                @@
                -same
                +same
                *** End Patch
                """));

    assertTrue(contextOnly.error());
    assertTrue(semanticNoOp.error());
    assertEquals("same\n", Files.readString(environmentRoot.resolve("unchanged.txt")));
  }

  private CodingToolsConfig config() {
    return new CodingToolsConfig(
        environmentRoot,
        environmentRoot,
        2000,
        50 * 1024,
        "bash",
        "rg",
        "fd",
        new InMemoryArtifactSink());
  }

  private static String patchArgs(String patchText) throws Exception {
    return AbstractCodingTool.OBJECT_MAPPER
        .createObjectNode()
        .put("patchText", patchText)
        .toString();
  }

  private Path pathWithDifferentStripe(String prefix, Path... reserved) {
    for (int index = 0; index < 1000; index++) {
      Path candidate = environmentRoot.resolve(prefix + "-" + index + ".txt");
      boolean conflicts =
          Arrays.stream(reserved)
              .anyMatch(
                  path -> FileMutations.stripeIndex(path) == FileMutations.stripeIndex(candidate));
      if (!conflicts) {
        return candidate;
      }
    }
    throw new AssertionError("could not find an independent mutation stripe");
  }

  private boolean waitForExists(Path path) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    return Files.exists(path);
  }

  private RecordingListener invokeAsync(Tool tool, String arguments) throws Exception {
    RecordingListener listener = new RecordingListener();
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("call", tool.descriptor().name(), arguments),
            Duration.ZERO),
        listener);
    return listener;
  }

  private ToolResult invoke(Tool tool, String arguments) throws Exception {
    RecordingListener listener = invokeAsync(tool, arguments);
    assertTrue(listener.await());
    return listener.result;
  }

  private static String text(ToolResult result) {
    return result.contents().stream()
        .map(ApplyPatchAndLspToolsTest::text)
        .reduce("", String::concat);
  }

  private static String text(ToolContent content) {
    return content instanceof TextToolContent value ? value.text() : "";
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile ToolResult result;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolResult result) {
      this.result = result;
      done.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return done.await(10, TimeUnit.SECONDS);
    }
  }
}
