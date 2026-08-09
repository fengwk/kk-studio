package fun.fengwk.kkstudio.plugin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.plugin.BranchView;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.PluginStateMode;
import fun.fengwk.kkstudio.harness.plugin.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.PluginToolResult;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Goal 插件的 branch snapshot、工具状态机、fork 与上下文投影测试。 */
class GoalPluginTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);

  private final GoalStateCodec codec = new GoalStateCodec();

  @Test
  void catalogFreezesGoalToolsStateOwnershipAndAccessModes() {
    PluginCatalog catalog = PluginCatalog.from(List.of(new GoalPlugin()));
    assertEquals(
        List.of("create_goal", "get_goal", "update_goal"),
        catalog.tools().stream().map(tool -> tool.descriptor().name()).toList());
    assertEquals("goal", catalog.findTool("create_goal").orElseThrow().id().pluginId().value());
    assertEquals(
        PluginStateMode.WRITE,
        catalog.findTool("create_goal").orElseThrow().stateAccesses().get(0).mode());
    assertEquals(
        PluginStateMode.READ,
        catalog.findTool("get_goal").orElseThrow().stateAccesses().get(0).mode());
    assertTrue(catalog.findCustomEntryType(GoalPlugin.ID, GoalPlugin.STATE_TYPE).isPresent());
    assertEquals(1, catalog.contextProjectors().size());
  }

  @Test
  void createUpdateAndGetReduceOnlyTheLatestSnapshotOnTheCurrentBranch() {
    BranchView root = branch();
    PluginToolResult created =
        new CreateGoalTool()
            .execute(
                new PluginToolContext(root, T1),
                new ToolCall(
                    "create-1",
                    CreateGoalTool.NAME,
                    "{\"objective\":\"Ship and verify\",\"tokenBudget\":1000}"));
    assertFalse(created.result().error());
    GoalState active = state(created);
    assertEquals("Ship and verify", active.objective());
    assertEquals(1000L, active.tokenBudget());
    assertEquals(GoalStatus.ACTIVE, active.status());
    assertEquals(T1, active.createdAt());
    BranchView activeBranch = append(root, payload(created), 2L, T1);

    PluginToolResult read =
        new GetGoalTool()
            .execute(
                new PluginToolContext(activeBranch, T1),
                new ToolCall("get-1", GetGoalTool.NAME, "{}"));
    assertFalse(read.result().error());
    assertTrue(text(read).contains("Ship and verify"));
    assertTrue(read.intents().isEmpty());

    PluginToolResult updated =
        new UpdateGoalTool()
            .execute(
                new PluginToolContext(activeBranch, T2),
                new ToolCall(
                    "update-1",
                    UpdateGoalTool.NAME,
                    "{\"status\":\"complete\",\"reason\":\"All checks passed\"}"));
    assertFalse(updated.result().error());
    GoalState complete = state(updated);
    assertEquals(GoalStatus.COMPLETE, complete.status());
    assertEquals("All checks passed", complete.reason());
    assertEquals(T1, complete.createdAt());
    assertEquals(T2, complete.updatedAt());

    BranchView completedBranch = append(activeBranch, payload(updated), 3L, T2);
    assertEquals(
        GoalStatus.COMPLETE, GoalToolSupport.latest(completedBranch).orElseThrow().status());
    assertTrue(
        GoalToolSupport.latest(root).isEmpty(), "a fork before create must not see the goal");
    assertEquals(
        GoalStatus.ACTIVE,
        GoalToolSupport.latest(activeBranch).orElseThrow().status(),
        "a sibling branch must not see a later terminal snapshot");
  }

  @Test
  void createReplacementPreservesCreationTimeAndReturnsAFullSnapshot() {
    PluginToolResult first =
        new CreateGoalTool()
            .execute(
                new PluginToolContext(branch(), T1),
                new ToolCall("create-1", CreateGoalTool.NAME, "{\"objective\":\"first\"}"));
    BranchView firstBranch = append(branch(), payload(first), 2L, T1);
    PluginToolResult replaced =
        new CreateGoalTool()
            .execute(
                new PluginToolContext(firstBranch, T2),
                new ToolCall(
                    "create-2",
                    CreateGoalTool.NAME,
                    "{\"objective\":\"second\",\"tokenBudget\":2000}"));
    GoalState state = state(replaced);
    assertEquals("second", state.objective());
    assertEquals(2000L, state.tokenBudget());
    assertEquals(T1, state.createdAt());
    assertEquals(T2, state.updatedAt());
    assertEquals(GoalStatus.ACTIVE, state.status());
  }

  @Test
  void updateRequiresAnActiveGoalAndInvalidArgumentsNeverEmitIntents() {
    UpdateGoalTool update = new UpdateGoalTool();
    PluginToolResult missing =
        update.execute(
            new PluginToolContext(branch(), T1),
            new ToolCall(
                "update-1", UpdateGoalTool.NAME, "{\"status\":\"complete\",\"reason\":\"done\"}"));
    assertTrue(missing.result().error());
    assertTrue(missing.intents().isEmpty());

    PluginToolResult invalid =
        new CreateGoalTool()
            .execute(
                new PluginToolContext(branch(), T1),
                new ToolCall(
                    "create-1", CreateGoalTool.NAME, "{\"objective\":\"x\",\"objective\":\"y\"}"));
    assertTrue(invalid.result().error());
    assertTrue(invalid.intents().isEmpty());

    PluginToolResult unknown =
        new CreateGoalTool()
            .execute(
                new PluginToolContext(branch(), T1),
                new ToolCall(
                    "create-2", CreateGoalTool.NAME, "{\"objective\":\"x\",\"unexpected\":true}"));
    assertTrue(unknown.result().error());
    assertTrue(unknown.intents().isEmpty());
  }

  @Test
  void getReportsMissingGoalAndRejectsUnknownArguments() {
    GetGoalTool get = new GetGoalTool();
    PluginToolResult missing =
        get.execute(
            new PluginToolContext(branch(), T1), new ToolCall("get-1", GetGoalTool.NAME, "{}"));
    assertFalse(missing.result().error());
    assertTrue(text(missing).contains("There is no current branch goal."));
    assertTrue(missing.intents().isEmpty());

    PluginToolResult invalid =
        get.execute(
            new PluginToolContext(branch(), T1),
            new ToolCall("get-2", GetGoalTool.NAME, "{\"unexpected\":true}"));
    assertTrue(invalid.result().error());
    assertTrue(invalid.intents().isEmpty());
  }

  @Test
  void updateRejectsATerminalGoalWithoutEmittingAnIntent() {
    GoalState complete = new GoalState("ship", null, GoalStatus.COMPLETE, "verified", T0, T1);
    BranchView completedBranch =
        append(
            branch(),
            new CustomEntryPayload(
                GoalPlugin.ID.value(),
                GoalPlugin.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(complete)),
            2L,
            T1);

    PluginToolResult result =
        new UpdateGoalTool()
            .execute(
                new PluginToolContext(completedBranch, T2),
                new ToolCall(
                    "update-1",
                    UpdateGoalTool.NAME,
                    "{\"status\":\"blocked\",\"reason\":\"still blocked\"}"));

    assertTrue(result.result().error());
    assertTrue(text(result).contains("complete"));
    assertTrue(result.intents().isEmpty());
  }

  @Test
  void goalStateAndStatusRejectInvalidDomainValues() {
    assertEquals(GoalStatus.BLOCKED, GoalStatus.parse("blocked"));
    assertThrows(IllegalArgumentException.class, () -> GoalStatus.parse("unknown"));
    assertThrows(IllegalArgumentException.class, () -> GoalStatus.parseTerminal("active"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new GoalState(" ", null, GoalStatus.ACTIVE, null, T0, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GoalState("ship", 0L, GoalStatus.ACTIVE, null, T0, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GoalState("ship", null, GoalStatus.ACTIVE, "premature", T0, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GoalState("ship", null, GoalStatus.COMPLETE, null, T0, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GoalState("ship", null, GoalStatus.ACTIVE, null, T1, T0));
  }

  @Test
  void activeContextIsProjectedButTerminalAndMissingGoalsAreSilent() {
    GoalContextProjector projector = new GoalContextProjector();
    assertTrue(projector.project(branch()).isEmpty());

    GoalState active = new GoalState("ship", null, GoalStatus.ACTIVE, null, T0, T1);
    BranchView activeBranch =
        append(
            branch(),
            new CustomEntryPayload(
                GoalPlugin.ID.value(),
                GoalPlugin.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(active)),
            2L,
            T1);
    assertEquals(1, projector.project(activeBranch).size());
    assertTrue(
        ((TextMessageContent) projector.project(activeBranch).get(0).contents().get(0))
            .text()
            .contains("\"objective\":\"ship\""));

    GoalState complete = new GoalState("ship", null, GoalStatus.COMPLETE, "verified", T0, T2);
    BranchView completeBranch =
        append(
            activeBranch,
            new CustomEntryPayload(
                GoalPlugin.ID.value(),
                GoalPlugin.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(complete)),
            3L,
            T2);
    assertTrue(projector.project(completeBranch).isEmpty());
  }

  @Test
  void stateCodecRejectsUnsupportedOrNonCanonicalSnapshots() {
    CustomEntryPayload unsupported = new CustomEntryPayload("goal", "state", 2, "{\"value\":1}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "goal",
                    "state",
                    1,
                    "{\"objective\":\"x\",\"tokenBudget\":null,\"status\":\"active\","
                        + "\"reason\":null,\"createdAt\":\""
                        + T0
                        + "\",\"updatedAt\":\""
                        + T1
                        + "\",\"extra\":true}")));
  }

  private GoalState state(PluginToolResult result) {
    return codec.decode(payload(result));
  }

  private static CustomEntryPayload payload(PluginToolResult result) {
    AppendCustomEntry append = assertInstanceOf(AppendCustomEntry.class, result.intents().get(0));
    return append.payload();
  }

  private static String text(PluginToolResult result) {
    return ((TextToolContent) result.result().contents().get(0)).text();
  }

  private static BranchView branch() {
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
                    List.of())),
            T0);
    return new BranchView(new EntryPath(List.of(root)));
  }

  private static BranchView append(
      BranchView branch, CustomEntryPayload payload, long entryId, Instant createdAt) {
    List<Entry> entries = new ArrayList<>(branch.path().entries());
    entries.add(
        new Entry(
            entryId,
            branch.path().root().sessionId(),
            branch.path().head().id(),
            payload,
            createdAt));
    return new BranchView(new EntryPath(entries));
  }
}
