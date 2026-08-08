package fun.fengwk.kkstudio.harness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class PluginIntentTest {

  @Test
  void appendCustomEntryWrapsTheFinalPayload() {
    CustomEntryPayload payload =
        new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"open\"}");
    AppendCustomEntry intent = new AppendCustomEntry(payload);
    assertEquals(payload, intent.payload());
    // 校验完全委托 payload 构造：非法 dataJson / schemaVersion / 标识符由 payload 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> new AppendCustomEntry(new CustomEntryPayload("com.example.goal", "goal", 0, "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppendCustomEntry(
                new CustomEntryPayload("com.example.goal", "goal", 1, "{\"a\": 1}")));
    assertThrows(NullPointerException.class, () -> new AppendCustomEntry(null));
  }

  @Test
  void appendCustomMessageWrapsTheFinalPayload() {
    AgentMessage system =
        new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("s")));
    CustomMessagePayload payload =
        new CustomMessagePayload(
            CustomMessagePayload.CORE_PLUGIN_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            system,
            CustomMessagePayload.CORE_DETAILS_JSON);
    assertEquals(payload, new AppendCustomMessage(payload).payload());
    // 校验完全委托 payload 构造：非 SYSTEM/USER role 由 payload 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppendCustomMessage(
                new CustomMessagePayload(
                    "core",
                    "message",
                    "message",
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("a"))),
                    "{}")));
    assertThrows(NullPointerException.class, () -> new AppendCustomMessage(null));
  }

  @Test
  void continueModelIsSingletonShaped() {
    assertEquals(new ContinueModel(), new ContinueModel());
  }

  @Test
  void pluginToolResultDefensivelyCopiesIntentsAndRejectsErrorEffects() {
    CustomEntryPayload payload =
        new CustomEntryPayload("goal", "state", 1, "{\"status\":\"active\"}");
    List<PluginIntent> mutable = new ArrayList<>(List.of(new AppendCustomEntry(payload)));
    ToolResult success =
        new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{}", false);

    PluginToolResult result = new PluginToolResult(success, mutable);
    mutable.clear();
    assertSame(success, result.result());
    assertEquals(List.of(new AppendCustomEntry(payload)), result.intents());
    assertThrows(UnsupportedOperationException.class, () -> result.intents().clear());
    assertTrue(PluginToolResult.withoutIntents(success).intents().isEmpty());

    assertThrows(NullPointerException.class, () -> new PluginToolResult(null, List.of()));
    assertThrows(NullPointerException.class, () -> new PluginToolResult(success, null));
    assertThrows(
        NullPointerException.class,
        () -> new PluginToolResult(success, Arrays.asList((PluginIntent) null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginToolResult(
                ToolResult.error("call-1", "failed"), List.of(new AppendCustomEntry(payload))));
  }

  @Test
  void pluginStateDeclarationAndToolContextAreStrictImmutableValues() {
    PluginStateDeclaration access = new PluginStateDeclaration("goal.state", PluginStateMode.WRITE);
    assertEquals("goal.state", access.customType());
    assertEquals(PluginStateMode.WRITE, access.mode());
    assertEquals(
        List.of(PluginStateMode.READ, PluginStateMode.WRITE), List.of(PluginStateMode.values()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginStateDeclaration("Goal", PluginStateMode.READ));
    assertThrows(NullPointerException.class, () -> new PluginStateDeclaration("goal", null));

    BranchView branch = branch();
    Instant executedAt = Instant.parse("2026-01-01T00:00:01Z");
    PluginToolContext context = new PluginToolContext(branch, executedAt);
    assertSame(branch, context.branch());
    assertEquals(executedAt, context.executedAt());
    assertThrows(NullPointerException.class, () -> new PluginToolContext(null, executedAt));
    assertThrows(NullPointerException.class, () -> new PluginToolContext(branch, null));
  }

  private static BranchView branch() {
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Entry root =
        new Entry(
            1L,
            1L,
            null,
            new RootPayload(
                new BranchSettings(
                    null,
                    "assistant",
                    new ModelSelection("provider", "model", "default"),
                    "medium",
                    List.of())),
            createdAt);
    return new BranchView(new EntryPath(List.of(root)));
  }
}
