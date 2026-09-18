package fun.fengwk.kkstudio.harness.builtin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Goal 特性的 branch snapshot、统一 Tool 执行、effects 产出与上下文投影测试。 */
class GoalFeatureTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);

  private final GoalStateCodec codec = new GoalStateCodec();

  /** Catalog 冻结验证：Tool 注册、State 要求声明与自定义 Entry Ownership。 */
  @Test
  void catalogFreezesGoalToolsStateOwnershipAndAccessModes() {
    BuiltinHarnessContributor contributor =
        new BuiltinHarnessContributor(
            stubTool("load_skill", ToolRequirements.environment()),
            stubTool("task", ToolRequirements.none()));
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    assertTrue(catalog.findTool("create_goal").isPresent());
    assertTrue(catalog.findTool("get_goal").isPresent());
    assertTrue(catalog.findTool("update_goal").isPresent());

    assertEquals(
        ToolVisibility.SELECTABLE,
        catalog.findTool("create_goal").orElseThrow().definition().visibility());
    assertEquals(
        "builtin", catalog.findTool("create_goal").orElseThrow().id().contributorId().value());

    assertEquals(
        new ToolRequirements(
            false,
            List.of(
                new StateDeclaration(BuiltinHarnessContributor.GOAL_STATE_TYPE, StateMode.WRITE))),
        catalog.findTool("create_goal").orElseThrow().requirements());
    assertEquals(
        new ToolRequirements(
            false,
            List.of(
                new StateDeclaration(BuiltinHarnessContributor.GOAL_STATE_TYPE, StateMode.READ))),
        catalog.findTool("get_goal").orElseThrow().requirements());
    assertEquals(
        new ToolRequirements(
            false,
            List.of(
                new StateDeclaration(BuiltinHarnessContributor.GOAL_STATE_TYPE, StateMode.WRITE))),
        catalog.findTool("update_goal").orElseThrow().requirements());

    assertTrue(
        catalog
            .findCustomEntryType(BuiltinHarnessContributor.ID, GoalFeature.STATE_TYPE)
            .isPresent());
    assertEquals(1, catalog.contextProjectors().size());
  }

  /** 三个 Goal Tool 的模型可见 name、副作用与 schema 符合稳定声明。 */
  @Test
  void exposesCanonicalGoalToolDescriptors() {
    ToolDescriptor create = new CreateGoalTool().descriptor();
    assertDescriptor(
        create,
        CreateGoalTool.NAME,
        ToolSideEffect.IDEMPOTENT,
        Set.of("objective"),
        Set.of("objective", "tokenBudget"));
    assertInstanceOf(StringSchema.class, create.inputSchema().properties().get("objective"));
    assertInstanceOf(IntegerSchema.class, create.inputSchema().properties().get("tokenBudget"));

    ToolDescriptor get = new GetGoalTool().descriptor();
    assertDescriptor(get, GetGoalTool.NAME, ToolSideEffect.READ_ONLY, Set.of(), Set.of());

    ToolDescriptor update = new UpdateGoalTool().descriptor();
    assertDescriptor(
        update,
        UpdateGoalTool.NAME,
        ToolSideEffect.IDEMPOTENT,
        Set.of("status", "reason"),
        Set.of("status", "reason"));
    EnumSchema status =
        assertInstanceOf(EnumSchema.class, update.inputSchema().properties().get("status"));
    assertEquals(List.of("complete", "blocked"), status.values());
    assertInstanceOf(StringSchema.class, update.inputSchema().properties().get("reason"));
  }

  /** 状态流转验证：create -> get -> update 正常推进分支 Goal 快照与 effects。 */
  @Test
  void createUpdateAndGetReduceOnlyTheLatestSnapshotOnTheCurrentBranch() {
    SimpleBranchView root = new SimpleBranchView();

    // 1. Create goal
    ToolOutcome createdOutcome =
        executeTool(
            new CreateGoalTool(),
            root,
            T1,
            "create-1",
            "{\"objective\":\"Ship and verify\",\"tokenBudget\":1000}");
    assertFalse(createdOutcome.result().error());
    assertEquals(1, createdOutcome.customEntries().size());
    AppendCustomEntry createEntry = createdOutcome.customEntries().get(0);
    assertEquals("goal.state", createEntry.customType());
    assertEquals(1, createEntry.schemaVersion());

    GoalState activeState =
        codec.decode(new CustomStateSnapshot(createEntry.schemaVersion(), createEntry.dataJson()));
    assertEquals("Ship and verify", activeState.objective());
    assertEquals(1000L, activeState.tokenBudget());
    assertEquals(GoalStatus.ACTIVE, activeState.status());
    assertNull(activeState.reason());
    assertEquals(T1, activeState.createdAt());
    assertEquals(T1, activeState.updatedAt());

    // 2. Branch with active goal -> Get goal
    SimpleBranchView activeBranch =
        root.withEntry(createEntry.schemaVersion(), createEntry.dataJson());
    ToolOutcome getOutcome = executeTool(new GetGoalTool(), activeBranch, T1, "get-1", "{}");
    assertFalse(getOutcome.result().error());
    assertTrue(getOutcome.customEntries().isEmpty());
    assertTrue(textContent(getOutcome.result()).contains("Ship and verify"));

    // 3. Update goal to complete
    ToolOutcome updateOutcome =
        executeTool(
            new UpdateGoalTool(),
            activeBranch,
            T2,
            "update-1",
            "{\"status\":\"complete\",\"reason\":\"All checks passed\"}");
    assertFalse(updateOutcome.result().error());
    assertEquals(1, updateOutcome.customEntries().size());
    AppendCustomEntry updateEntry = updateOutcome.customEntries().get(0);

    GoalState completeState =
        codec.decode(new CustomStateSnapshot(updateEntry.schemaVersion(), updateEntry.dataJson()));
    assertEquals("Ship and verify", completeState.objective());
    assertEquals(GoalStatus.COMPLETE, completeState.status());
    assertEquals("All checks passed", completeState.reason());
    assertEquals(T1, completeState.createdAt());
    assertEquals(T2, completeState.updatedAt());

    // 4. Update on completed goal must fail
    SimpleBranchView completedBranch =
        activeBranch.withEntry(updateEntry.schemaVersion(), updateEntry.dataJson());
    ToolOutcome invalidUpdate =
        executeTool(
            new UpdateGoalTool(),
            completedBranch,
            T2,
            "update-2",
            "{\"status\":\"blocked\",\"reason\":\"Cannot modify completed goal\"}");
    assertTrue(invalidUpdate.result().error());
    assertTrue(invalidUpdate.customEntries().isEmpty());
    assertTrue(textContent(invalidUpdate.result()).contains("cannot be updated"));
  }

  /** 当缺少 Goal 时 UpdateGoalTool 应产生无 effects 的错误结果。 */
  @Test
  void updateFailsWhenNoGoalIsSet() {
    SimpleBranchView emptyBranch = new SimpleBranchView();
    ToolOutcome outcome =
        executeTool(
            new UpdateGoalTool(),
            emptyBranch,
            T1,
            "update-no-goal",
            "{\"status\":\"complete\",\"reason\":\"nothing\"}");
    assertTrue(outcome.result().error());
    assertTrue(outcome.customEntries().isEmpty());
    assertTrue(textContent(outcome.result()).contains("No goal is set"));
  }

  /** 上下文缺失时工具应返回错误且无 effects。 */
  @Test
  void toolsHandleMissingExecutionContextGracefully() {
    ToolOutcome createOutcome =
        executeToolWithoutContext(new CreateGoalTool(), "call-1", "{\"objective\":\"test\"}");
    assertTrue(createOutcome.result().error());
    assertTrue(createOutcome.customEntries().isEmpty());

    ToolOutcome getOutcome = executeToolWithoutContext(new GetGoalTool(), "call-2", "{}");
    assertTrue(getOutcome.result().error());
    assertTrue(getOutcome.customEntries().isEmpty());

    ToolOutcome updateOutcome =
        executeToolWithoutContext(
            new UpdateGoalTool(), "call-3", "{\"status\":\"complete\",\"reason\":\"r\"}");
    assertTrue(updateOutcome.result().error());
    assertTrue(updateOutcome.customEntries().isEmpty());
  }

  /** 验证 terminal-at-most-once：若 listener 同步抛出 RuntimeException，工具不会在 catch 中再次触发 onComplete。 */
  @Test
  void listenerThrowingExceptionDoesNotTriggerSecondaryCallback() {
    SimpleBranchView root = new SimpleBranchView();
    AtomicInteger calls = new AtomicInteger();
    ToolExecutionListener throwingListener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            calls.incrementAndGet();
            throw new RuntimeException("listener sync failure");
          }

          @Override
          public void onError(Throwable error) {}
        };

    // 1. CreateGoalTool
    ToolExecutionContext context1 =
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), T1, root);
    ToolExecutionRequest req1 =
        new ToolExecutionRequest(
            new CreateGoalTool().descriptor(),
            new ToolCall("call-1", "create_goal", "{\"objective\":\"obj\"}"),
            Duration.ZERO,
            context1);
    assertThrows(
        RuntimeException.class, () -> new CreateGoalTool().execute(req1, throwingListener));
    assertEquals(1, calls.get(), "create_goal must invoke onComplete exactly once");

    // 2. GetGoalTool
    calls.set(0);
    ToolExecutionContext context2 =
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), T1, root);
    ToolExecutionRequest req2 =
        new ToolExecutionRequest(
            new GetGoalTool().descriptor(),
            new ToolCall("call-2", "get_goal", "{}"),
            Duration.ZERO,
            context2);
    assertThrows(RuntimeException.class, () -> new GetGoalTool().execute(req2, throwingListener));
    assertEquals(1, calls.get(), "get_goal must invoke onComplete exactly once");

    // 3. UpdateGoalTool
    GoalState activeState = new GoalState("obj", null, GoalStatus.ACTIVE, null, T0, T1);
    SimpleBranchView branchWithGoal =
        root.withEntry(GoalStateCodec.SCHEMA_VERSION, codec.encode(activeState));
    calls.set(0);
    ToolExecutionContext context3 =
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), T2, branchWithGoal);
    ToolExecutionRequest req3 =
        new ToolExecutionRequest(
            new UpdateGoalTool().descriptor(),
            new ToolCall("call-3", "update_goal", "{\"status\":\"complete\",\"reason\":\"done\"}"),
            Duration.ZERO,
            context3);
    assertThrows(
        RuntimeException.class, () -> new UpdateGoalTool().execute(req3, throwingListener));
    assertEquals(1, calls.get(), "update_goal must invoke onComplete exactly once");
  }

  /** GoalContextProjector 仅对 ACTIVE 状态产生 ContextFragment 注入。 */
  @Test
  void projectorProducesContextOnlyForActiveGoal() {
    GoalContextProjector projector = new GoalContextProjector();
    SimpleBranchView emptyBranch = new SimpleBranchView();
    assertTrue(projector.project(emptyBranch).isEmpty());

    GoalState activeState = new GoalState("active goal", 500L, GoalStatus.ACTIVE, null, T0, T0);
    SimpleBranchView activeBranch =
        emptyBranch.withEntry(GoalStateCodec.SCHEMA_VERSION, codec.encode(activeState));
    List<ContextFragment> fragments = projector.project(activeBranch);
    assertEquals(1, fragments.size());
    assertTrue(fragments.get(0).text().contains("active goal"));

    GoalState completeState =
        new GoalState("complete goal", null, GoalStatus.COMPLETE, "done", T0, T1);
    SimpleBranchView completeBranch =
        activeBranch.withEntry(GoalStateCodec.SCHEMA_VERSION, codec.encode(completeState));
    assertTrue(projector.project(completeBranch).isEmpty());
  }

  private static ToolOutcome executeTool(
      Tool tool, BranchView branch, Instant executedAt, String callId, String argumentsJson) {
    ToolExecutionContext context =
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), executedAt, branch);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(callId, tool.descriptor().name(), argumentsJson),
            Duration.ZERO,
            context);
    AtomicReference<ToolOutcome> ref = new AtomicReference<>();
    tool.execute(
        request,
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            ref.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        });
    assertNotNull(ref.get(), "listener.onComplete must be called synchronously");
    return ref.get();
  }

  private static ToolOutcome executeToolWithoutContext(
      Tool tool, String callId, String argumentsJson) {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(callId, tool.descriptor().name(), argumentsJson),
            Duration.ZERO);
    AtomicReference<ToolOutcome> ref = new AtomicReference<>();
    tool.execute(
        request,
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            ref.set(outcome);
          }

          @Override
          public void onError(Throwable error) {}
        });
    assertNotNull(ref.get(), "listener.onComplete must be called");
    return ref.get();
  }

  private static String textContent(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  private static void assertDescriptor(
      ToolDescriptor descriptor,
      String expectedName,
      ToolSideEffect expectedSideEffect,
      Set<String> requiredParams,
      Set<String> allParams) {
    assertEquals(expectedName, descriptor.name());
    assertEquals(expectedName, descriptor.rendererKey());
    assertEquals(expectedSideEffect, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());
    assertEquals(requiredParams, descriptor.inputSchema().required());
    assertEquals(allParams, descriptor.inputSchema().properties().keySet());
  }

  private static Tool stubTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            name + " description",
            name,
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolRequirements requirements() {
        return requirements;
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

  private static final class SimpleBranchView implements BranchView {
    private final List<CustomStateSnapshot> entries;

    private SimpleBranchView() {
      this(List.of());
    }

    private SimpleBranchView(List<CustomStateSnapshot> entries) {
      this.entries = List.copyOf(entries);
    }

    private SimpleBranchView withEntry(int version, String dataJson) {
      List<CustomStateSnapshot> next = new ArrayList<>(entries);
      next.add(new CustomStateSnapshot(version, dataJson));
      return new SimpleBranchView(next);
    }

    @Override
    public List<CustomStateSnapshot> customEntries(String customType) {
      if ("goal.state".equals(customType)) {
        return entries;
      }
      return List.of();
    }

    @Override
    public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
      List<CustomStateSnapshot> list = customEntries(customType);
      return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }
  }
}
