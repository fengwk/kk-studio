package fun.fengwk.kkstudio.harness.daemon.coding;

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
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                apply.descriptor(),
                new ToolCall("bad", "apply_patch", "{\"patchText\":1}"),
                Duration.ZERO));

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
  void applyPatchAddsUpdatesDeletesWithPreflightAtomicityAndRejectsMove() throws Exception {
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

    ToolResult move =
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
    assertTrue(move.error());
    assertTrue(text(move).contains("Move operations are not supported"));
    assertEquals("alpha\ngamma\n", Files.readString(environmentRoot.resolve("keep.txt")));
    assertFalse(Files.exists(environmentRoot.resolve("moved.txt")));
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

  private ToolResult invoke(Tool tool, String arguments) throws Exception {
    RecordingListener listener = new RecordingListener();
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("call", tool.descriptor().name(), arguments),
            Duration.ZERO),
        listener);
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
