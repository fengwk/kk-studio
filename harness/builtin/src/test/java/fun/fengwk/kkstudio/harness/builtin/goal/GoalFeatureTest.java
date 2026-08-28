package fun.fengwk.kkstudio.harness.builtin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContext;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Goal 特性的 branch snapshot、工具状态机、fork 与上下文投影测试。 */
class GoalFeatureTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);

  private final GoalStateCodec codec = new GoalStateCodec();

  @Test
  void catalogFreezesGoalToolsStateOwnershipAndAccessModes() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(stubTool("load_skill"), stubTool("task"));
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    assertTrue(catalog.findTool("create_goal").isPresent());
    assertTrue(catalog.findTool("get_goal").isPresent());
    assertTrue(catalog.findTool("update_goal").isPresent());

    assertEquals(
        BuiltinToolIds.GOAL_CREATE,
        catalog.findTool("create_goal").orElseThrow().definition().id());
    assertEquals(
        BuiltinToolIds.GOAL_GET, catalog.findTool("get_goal").orElseThrow().definition().id());
    assertEquals(
        BuiltinToolIds.GOAL_UPDATE,
        catalog.findTool("update_goal").orElseThrow().definition().id());

    assertEquals(
        AgentToolBackend.DECLARATIVE,
        catalog.findTool("create_goal").orElseThrow().definition().backend());
    assertEquals(
        "builtin", catalog.findTool("create_goal").orElseThrow().id().contributorId().value());

    assertEquals(
        StateMode.WRITE,
        ((DeclarativeToolContribution) catalog.findTool("create_goal").orElseThrow())
            .stateAccesses()
            .get(0)
            .mode());
    assertEquals(
        StateMode.READ,
        ((DeclarativeToolContribution) catalog.findTool("get_goal").orElseThrow())
            .stateAccesses()
            .get(0)
            .mode());
    assertEquals(
        StateMode.WRITE,
        ((DeclarativeToolContribution) catalog.findTool("update_goal").orElseThrow())
            .stateAccesses()
            .get(0)
            .mode());

    assertTrue(
        catalog
            .findCustomEntryType(BuiltinHarnessContributor.ID, GoalFeature.STATE_TYPE)
            .isPresent());
    assertEquals(1, catalog.contextProjectors().size());
  }

  /** 三个 Goal Tool 的版本、可见类型、副作用与 schema 精确对齐 durable goal 协议。 */
  @Test
  void exposesCanonicalGoalToolDescriptors() {
    ToolDescriptor create = new CreateGoalTool().descriptor();
    assertDescriptor(
        create,
        CreateGoalTool.NAME,
        CreateGoalTool.VERSION,
        ToolSideEffect.IDEMPOTENT,
        Set.of("objective"),
        Set.of("objective", "tokenBudget"));
    assertInstanceOf(ToolStringSchema.class, create.inputSchema().properties().get("objective"));
    assertInstanceOf(ToolIntegerSchema.class, create.inputSchema().properties().get("tokenBudget"));

    ToolDescriptor get = new GetGoalTool().descriptor();
    assertDescriptor(
        get, GetGoalTool.NAME, GetGoalTool.VERSION, ToolSideEffect.READ_ONLY, Set.of(), Set.of());

    ToolDescriptor update = new UpdateGoalTool().descriptor();
    assertDescriptor(
        update,
        UpdateGoalTool.NAME,
        UpdateGoalTool.VERSION,
        ToolSideEffect.IDEMPOTENT,
        Set.of("status", "reason"),
        Set.of("status", "reason"));
    ToolEnumSchema status =
        assertInstanceOf(ToolEnumSchema.class, update.inputSchema().properties().get("status"));
    assertEquals(List.of("complete", "blocked"), status.values());
    assertInstanceOf(ToolStringSchema.class, update.inputSchema().properties().get("reason"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolCall(
                    "create-extra",
                    CreateGoalTool.NAME,
                    "{\"objective\":\"ship\",\"unexpected\":true}")
                .validateFor(create));
  }

  @Test
  void createUpdateAndGetReduceOnlyTheLatestSnapshotOnTheCurrentBranch() {
    List<Entry> history = baseHistory();
    BranchView root = new BranchView(new EntryPath(history));

    DeclarativeToolResult created =
        new CreateGoalTool()
            .execute(
                new DeclarativeToolContext(root, T1),
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

    List<Entry> activeHistory = appendHistory(history, payload(created), new UUID(0L, 2), T1);
    BranchView activeBranch = new BranchView(new EntryPath(activeHistory));

    DeclarativeToolResult read =
        new GetGoalTool()
            .execute(
                new DeclarativeToolContext(activeBranch, T1),
                new ToolCall("get-1", GetGoalTool.NAME, "{}"));
    assertFalse(read.result().error());
    assertTrue(text(read).contains("Ship and verify"));
    assertTrue(read.intents().isEmpty());

    DeclarativeToolResult updated =
        new UpdateGoalTool()
            .execute(
                new DeclarativeToolContext(activeBranch, T2),
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

    List<Entry> completedHistory =
        appendHistory(activeHistory, payload(updated), new UUID(0L, 3), T2);
    BranchView completedBranch = new BranchView(new EntryPath(completedHistory));

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
    List<Entry> history = baseHistory();
    BranchView root = new BranchView(new EntryPath(history));

    DeclarativeToolResult first =
        new CreateGoalTool()
            .execute(
                new DeclarativeToolContext(root, T1),
                new ToolCall("create-1", CreateGoalTool.NAME, "{\"objective\":\"first\"}"));
    List<Entry> firstHistory = appendHistory(history, payload(first), new UUID(0L, 2), T1);
    BranchView firstBranch = new BranchView(new EntryPath(firstHistory));

    DeclarativeToolResult replaced =
        new CreateGoalTool()
            .execute(
                new DeclarativeToolContext(firstBranch, T2),
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
    List<Entry> history = baseHistory();
    BranchView root = new BranchView(new EntryPath(history));

    UpdateGoalTool update = new UpdateGoalTool();
    DeclarativeToolResult missing =
        update.execute(
            new DeclarativeToolContext(root, T1),
            new ToolCall(
                "update-1", UpdateGoalTool.NAME, "{\"status\":\"complete\",\"reason\":\"done\"}"));
    assertTrue(missing.result().error());
    assertTrue(missing.intents().isEmpty());

    DeclarativeToolResult invalid =
        new CreateGoalTool()
            .execute(
                new DeclarativeToolContext(root, T1),
                new ToolCall(
                    "create-1", CreateGoalTool.NAME, "{\"objective\":\"x\",\"objective\":\"y\"}"));
    assertTrue(invalid.result().error());
    assertTrue(invalid.intents().isEmpty());

    DeclarativeToolResult unknown =
        new CreateGoalTool()
            .execute(
                new DeclarativeToolContext(root, T1),
                new ToolCall(
                    "create-2", CreateGoalTool.NAME, "{\"objective\":\"x\",\"unexpected\":true}"));
    assertTrue(unknown.result().error());
    assertTrue(unknown.intents().isEmpty());
  }

  @Test
  void getReportsMissingGoalAndRejectsUnknownArguments() {
    List<Entry> history = baseHistory();
    BranchView root = new BranchView(new EntryPath(history));

    GetGoalTool get = new GetGoalTool();
    DeclarativeToolResult missing =
        get.execute(
            new DeclarativeToolContext(root, T1), new ToolCall("get-1", GetGoalTool.NAME, "{}"));
    assertFalse(missing.result().error());
    assertTrue(text(missing).contains("There is no current branch goal."));
    assertTrue(missing.intents().isEmpty());

    DeclarativeToolResult invalid =
        get.execute(
            new DeclarativeToolContext(root, T1),
            new ToolCall("get-2", GetGoalTool.NAME, "{\"unexpected\":true}"));
    assertTrue(invalid.result().error());
    assertTrue(invalid.intents().isEmpty());
  }

  @Test
  void updateRejectsATerminalGoalWithoutEmittingAnIntent() {
    GoalState complete = new GoalState("ship", null, GoalStatus.COMPLETE, "verified", T0, T1);
    List<Entry> history =
        appendHistory(
            baseHistory(),
            new CustomEntryPayload(
                GoalFeature.CONTRIBUTOR_ID.value(),
                GoalFeature.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(complete)),
            new UUID(0L, 2),
            T1);
    BranchView completedBranch = new BranchView(new EntryPath(history));

    DeclarativeToolResult result =
        new UpdateGoalTool()
            .execute(
                new DeclarativeToolContext(completedBranch, T2),
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
    List<Entry> history = baseHistory();
    assertTrue(projector.project(new BranchView(new EntryPath(history))).isEmpty());

    GoalState active = new GoalState("ship", null, GoalStatus.ACTIVE, null, T0, T1);
    List<Entry> activeHistory =
        appendHistory(
            history,
            new CustomEntryPayload(
                GoalFeature.CONTRIBUTOR_ID.value(),
                GoalFeature.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(active)),
            new UUID(0L, 2),
            T1);
    BranchView activeBranch = new BranchView(new EntryPath(activeHistory));
    assertEquals(1, projector.project(activeBranch).size());
    assertTrue(
        ((TextMessageContent) projector.project(activeBranch).get(0).contents().get(0))
            .text()
            .contains("\"objective\":\"ship\""));

    GoalState complete = new GoalState("ship", null, GoalStatus.COMPLETE, "verified", T0, T2);
    List<Entry> completeHistory =
        appendHistory(
            activeHistory,
            new CustomEntryPayload(
                GoalFeature.CONTRIBUTOR_ID.value(),
                GoalFeature.STATE_TYPE,
                GoalStateCodec.SCHEMA_VERSION,
                codec.encode(complete)),
            new UUID(0L, 3),
            T2);
    BranchView completeBranch = new BranchView(new EntryPath(completeHistory));
    assertTrue(projector.project(completeBranch).isEmpty());
  }

  @Test
  void stateCodecRejectsUnsupportedOrNonCanonicalSnapshots() {
    CustomEntryPayload unsupported =
        new CustomEntryPayload("builtin", "goal.state", 2, "{\"value\":1}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "builtin",
                    "goal.state",
                    1,
                    "{\"objective\":\"x\",\"tokenBudget\":null,\"status\":\"active\","
                        + "\"reason\":null,\"createdAt\":\""
                        + T0
                        + "\",\"updatedAt\":\""
                        + T1
                        + "\",\"extra\":true}")));
  }

  private GoalState state(DeclarativeToolResult result) {
    return codec.decode(payload(result));
  }

  private static CustomEntryPayload payload(DeclarativeToolResult result) {
    AppendCustomEntry append = assertInstanceOf(AppendCustomEntry.class, result.intents().get(0));
    return append.payload();
  }

  private static String text(DeclarativeToolResult result) {
    return ((TextToolContent) result.result().contents().get(0)).text();
  }

  private static void assertDescriptor(
      ToolDescriptor descriptor,
      String name,
      String version,
      ToolSideEffect sideEffect,
      Set<String> required,
      Set<String> properties) {
    assertEquals(name, descriptor.name());
    assertEquals(version, descriptor.version());
    assertEquals(name, descriptor.rendererKey());
    assertEquals(sideEffect, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.timeout());
    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(required, schema.required());
    assertEquals(properties, schema.properties().keySet());
    assertFalse(schema.additionalProperties());
  }

  private static List<Entry> baseHistory() {
    UUID rootId = new UUID(0L, 1L);
    Entry root =
        new Entry(
            rootId,
            rootId,
            null,
            new RootPayload(
                new BranchSettings(
                    null, "assistant", new ModelSelection("provider", "model", "default"))),
            T0);
    return List.of(root);
  }

  private static List<Entry> appendHistory(
      List<Entry> history, CustomEntryPayload payload, UUID entryId, Instant createdAt) {
    List<Entry> entries = new ArrayList<>(history);
    Entry head = entries.get(entries.size() - 1);
    entries.add(new Entry(entryId, entries.get(0).sessionId(), head.id(), payload, createdAt));
    return List.copyOf(entries);
  }

  private static Tool stubTool(String name) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "1",
            name + " description",
            name,
            new ToolParamsSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
      }
    };
  }
}
