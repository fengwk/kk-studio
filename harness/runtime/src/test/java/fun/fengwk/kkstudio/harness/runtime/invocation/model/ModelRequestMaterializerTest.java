package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryInput;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.GoalMessages;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** ModelRequestMaterializer 纯投影：相同 spec+path 可确定重放，编码体积与普通历史长度无关，压缩只使用 Entry IDs。 */
class ModelRequestMaterializerTest {
  private static final UUID OWNER = id(1L);
  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  private static final ModelRequestMaterializer MATERIALIZER = new ModelRequestMaterializer();
  private static final ModelRequestSpecJsonCodec CODEC = new ModelRequestSpecJsonCodec();

  @Test
  void rematerializesTheSameLogicalRequestAfterCodecRoundTrip() {
    EntryPath path = conversationPath(2);
    ModelRequestSpec spec = liveSpec(bashBinding());

    ProviderRequest first = MATERIALIZER.materialize(path, spec);
    ModelRequestSpec restored = CODEC.decode(CODEC.encode(spec));
    ProviderRequest second = MATERIALIZER.materialize(path, restored);

    assertEquals(first, second);
    assertEquals("Test system instruction.", first.systemInstruction());
    assertEquals(4, first.messages().size());
    assertEquals("user-1", textOf(first.messages().get(0)));
    assertEquals("reply-1", textOf(first.messages().get(1)));
    assertEquals("user-2", textOf(first.messages().get(2)));
    assertEquals("reply-2", textOf(first.messages().get(3)));
    assertEquals(1, first.tools().size());
    assertEquals("bash", first.tools().get(0).name());
    assertEquals(
        new SchemaJsonCodec().encode(bashBinding().descriptor().inputSchema()),
        first.tools().get(0).inputSchemaJson());
  }

  @Test
  void encodedSpecSizeIsIndependentOfOrdinaryHistoryLength() {
    // 两条独立构造的等价 spec：模拟 resolver 只冻结 systemInstruction/bindings，不把普通历史写入 durable spec。
    EntryPath shortPath = conversationPath(1);
    EntryPath longPath = conversationPath(32);
    ModelRequestSpec shortSpec = specFromFrozenInputs(shortPath, bashBinding());
    ModelRequestSpec longSpec = specFromFrozenInputs(longPath, bashBinding());
    String shortEncoded = CODEC.encode(shortSpec);
    String longEncoded = CODEC.encode(longSpec);

    ProviderRequest shortRequest = MATERIALIZER.materialize(shortPath, shortSpec);
    ProviderRequest longRequest = MATERIALIZER.materialize(longPath, longSpec);

    assertEquals(shortSpec, longSpec);
    assertEquals(shortEncoded, longEncoded);
    assertEquals("Test system instruction.", shortRequest.systemInstruction());
    assertEquals("Test system instruction.", longRequest.systemInstruction());
    assertFalse(shortEncoded.contains("user-1"));
    assertFalse(longEncoded.contains("user-32"));
    assertFalse(longEncoded.contains("reply-32"));
    assertTrue(longRequest.messages().size() > shortRequest.messages().size());
    assertEquals(2, shortRequest.messages().size());
    assertEquals(64, longRequest.messages().size());
  }

  @Test
  void liveProjectionSkipsBoundariesErrorsAndUsesLatestCompleteCompaction() {
    List<Entry> entries = new ArrayList<>();
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    entries.add(entry(2, 1, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(3, 2, user("old-user")));
    entries.add(entry(4, 3, assistant("old-reply")));
    entries.add(
        entry(5, 4, new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, false, null, null)));
    CompactionStart completed =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, id(4L), null, null);
    entries.add(entry(6, 5, resolvedStart(TurnStartReason.COMPACTION, completed)));
    entries.add(entry(7, 6, new CompactionPayload("kept summary", null)));
    entries.add(
        entry(8, 7, new TurnEndPayload(id(6L), TurnEndOutcome.COMPLETED, false, null, null)));
    entries.add(entry(9, 8, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(10, 9, user("kept-user")));
    entries.add(
        entry(
            11,
            10,
            new AssistantErrorPayload(new AssistantError("PLANNING_FAILED", "rejected"), null)));
    entries.add(
        entry(
            12,
            11,
            new TurnEndPayload(
                id(9L), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null)));
    entries.add(entry(13, 12, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(14, 13, user("latest-user")));

    ProviderRequest request =
        MATERIALIZER.materialize(new EntryPath(entries), liveSpec(bashBinding()));

    assertEquals(5, request.messages().size());
    assertEquals("Test system instruction.", request.systemInstruction());
    assertEquals(
        CompactionPrompts.compactedContext("kept summary"), textOf(request.messages().get(0)));
    assertEquals(agentText(GoalMessages.backgroundCleared()), textOf(request.messages().get(1)));
    assertEquals("old-reply", textOf(request.messages().get(2)));
    assertEquals("kept-user", textOf(request.messages().get(3)));
    assertEquals("latest-user", textOf(request.messages().get(4)));
  }

  /**
   * 测试意图：压缩冻结边界的 Goal 作为有界 USER 级背景紧邻摘要插入（派生自 COMPACTION TURN_START 冻结 settings），不进
   * systemInstruction。
   */
  @Test
  void compactionInjectsFrozenGoalBackground() {
    ProviderRequest request =
        MATERIALIZER.materialize(goalHistoryPath(true, false), liveSpec(bashBinding()));

    assertEquals("Test system instruction.", request.systemInstruction());
    assertEquals(
        CompactionPrompts.compactedContext("kept summary"), textOf(request.messages().get(0)));
    assertEquals(
        agentText(GoalMessages.backgroundSet("ship it")), textOf(request.messages().get(1)));
    assertTrue(textOf(request.messages().get(1)).contains("ship it"));
  }

  /** 测试意图：背景逐字来自冻结快照，不因 Goal 输入消息仍在近期消息中而跳过或去重；后续变更由真实输入消息自身携带。 */
  @Test
  void compactionSeedsFrozenGoalBackgroundRegardlessOfRetainedInput() {
    ProviderRequest retained =
        MATERIALIZER.materialize(goalHistoryPath(false, false), liveSpec(bashBinding()));

    assertEquals(
        agentText(GoalMessages.backgroundSet("ship it")), textOf(retained.messages().get(1)));
    assertEquals(agentText(GoalMessages.inputSet("ship it")), textOf(retained.messages().get(2)));
  }

  /** 测试意图：压缩边界没有生效 Goal 时只陈述该事实，绝不复活更早的目标，也不声称发生过清除。 */
  @Test
  void compactionAfterClearStatesNoActiveGoalWithoutReviving() {
    ProviderRequest cleared =
        MATERIALIZER.materialize(goalHistoryPath(true, true), liveSpec(bashBinding()));

    assertEquals(agentText(GoalMessages.backgroundCleared()), textOf(cleared.messages().get(1)));
    assertTrue(textOf(cleared.messages().get(1)).contains("No active user-set goal."));
    assertTrue(
        cleared.messages().stream().noneMatch(message -> textOf(message).contains("ship it")));
  }

  /** 测试意图：压缩边界冻结 Goal 后，后续真实清除输入不得改写已冻结背景（共享前缀逐消息相等），且清除以显式输入消息出现在真实近期消息末尾。 */
  @Test
  void clearAfterCompactionKeepsSharedPrefixAndEndsWithExplicitInput() {
    ProviderRequest before =
        MATERIALIZER.materialize(goalHistoryPath(true, false), liveSpec(bashBinding()));
    ProviderRequest after =
        MATERIALIZER.materialize(goalThenClearAfterCompactionPath(), liveSpec(bashBinding()));

    for (int i = 0; i < before.messages().size(); i++) {
      assertEquals(textOf(before.messages().get(i)), textOf(after.messages().get(i)));
    }
    assertEquals(before.messages().size() + 2, after.messages().size());
    assertEquals(
        agentText(GoalMessages.inputCleared()),
        textOf(after.messages().get(after.messages().size() - 2)));
    assertEquals("cleared reply", textOf(after.messages().get(after.messages().size() - 1)));
  }

  @Test
  void compactionRequestRebuildsTheSameSummaryPromptFromEntryIds() {
    EntryPath history = conversationPath(2);
    CompactionStart compaction =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, id(4L), null, null);
    List<Entry> entries = new ArrayList<>(history.entries());
    entries.add(
        entry(id(10L), history.head().id(), resolvedStart(TurnStartReason.COMPACTION, compaction)));
    EntryPath path = new EntryPath(entries);
    CompactionSummaryInput input = CompactionPlanner.reconstructSummaryInput(path, compaction);
    ModelRequestSpec spec = compactionSpec();

    ProviderRequest request = MATERIALIZER.materialize(path, spec);

    assertEquals(1, request.messages().size());
    assertEquals(spec.systemInstruction(), request.systemInstruction());
    assertEquals(
        CompactionPrompts.summaryUserPrompt(input.messages(), input.previousSummary()),
        textOf(request.messages().get(0)));
    assertTrue(request.tools().isEmpty());
  }

  @Test
  void liveHistoryTransmitsReplayState() {
    ProviderReplayState replayState = sampleReplayState();
    List<Entry> entries = new ArrayList<>();
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    entries.add(entry(2, 1, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(3, 2, user("user question")));
    // 带 replayState 的 assistant entry
    entries.add(
        new Entry(id(4L), id(100L), id(3L), assistant("assistant answer"), T0, replayState));

    ProviderRequest request =
        MATERIALIZER.materialize(new EntryPath(entries), liveSpec(bashBinding()));

    assertEquals(2, request.messages().size());
    assertEquals("Test system instruction.", request.systemInstruction());
    // index 0: user
    // index 1: assistant
    ProviderMessage assistantMsg = request.messages().get(1);
    assertEquals(replayState, assistantMsg.replayState());
  }

  @Test
  void compactionSuppressesOldTailReplayStateAndPreservesNewTail() {
    ProviderReplayState oldReplayState = sampleReplayState();
    ProviderReplayState newReplayState = sampleReplayState();

    List<Entry> entries = new ArrayList<>();
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    entries.add(entry(2, 1, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(3, 2, user("old user")));
    // old tail assistant: 带有 oldReplayState，且处于 cutEntryId(4) 到 compaction result(7) 之间
    entries.add(
        new Entry(id(4L), id(100L), id(3L), assistant("old assistant"), T0, oldReplayState));
    entries.add(
        entry(5, 4, new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, false, null, null)));

    CompactionStart completed =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, id(4L), null, null);
    entries.add(entry(6, 5, resolvedStart(TurnStartReason.COMPACTION, completed)));
    entries.add(entry(7, 6, new CompactionPayload("summary", null)));
    entries.add(
        entry(8, 7, new TurnEndPayload(id(6L), TurnEndOutcome.COMPLETED, false, null, null)));

    // compaction 之后的新 turn
    entries.add(entry(9, 8, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(10, 9, user("new user")));
    // new tail assistant: 带有 newReplayState
    entries.add(
        new Entry(id(11L), id(100L), id(10L), assistant("new assistant"), T0, newReplayState));

    ProviderRequest request =
        MATERIALIZER.materialize(new EntryPath(entries), liveSpec(bashBinding()));

    // 结构：
    // 0: summary (USER wrapper, null replay)
    // 1: frozen compaction-boundary Goal background (USER, null replay)
    // 2: old assistant (retained old tail: replay 必须被压制为 null)
    // 3: new user (null replay)
    // 4: new assistant (post-compaction new tail: replay 必须保留)
    assertEquals(5, request.messages().size());
    assertEquals("Test system instruction.", request.systemInstruction());

    assertEquals(CompactionPrompts.compactedContext("summary"), textOf(request.messages().get(0)));
    assertFalse(request.messages().get(0).hasReplayState());

    assertEquals(agentText(GoalMessages.backgroundCleared()), textOf(request.messages().get(1)));
    assertFalse(request.messages().get(1).hasReplayState());

    assertEquals("old assistant", textOf(request.messages().get(2)));
    assertFalse(
        request.messages().get(2).hasReplayState(),
        "retained old tail assistant entry's replay state must be suppressed");

    assertEquals("new user", textOf(request.messages().get(3)));
    assertFalse(request.messages().get(3).hasReplayState());

    assertEquals("new assistant", textOf(request.messages().get(4)));
    assertTrue(
        request.messages().get(4).hasReplayState(),
        "post-compaction assistant entry's replay state must be preserved");
    assertEquals(newReplayState, request.messages().get(4).replayState());
  }

  /**
   * 意图：ProviderRequest 的 native 资格由 spec 冻结的 binding 决定——冻结 Environment 与当前同名工具一致的调用保持 native tool
   * adjacency（assistant call block + TOOL 结果）；Environment 切换后旧调用必须从 ASSISTANT wire 移除、其结果不再产生 TOOL
   * 消息，而与 结果配对后合并为单条 USER {@code Previous context:} 自然语言上下文。
   */
  @Test
  void environmentSwitchDowngradesFrozenToolHistoryInProviderRequest() {
    EntryPath path = toolHistoryPath();

    ProviderRequest sameEnvironment =
        MATERIALIZER.materialize(path, liveSpec(environmentBinding("dev")));
    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL),
        sameEnvironment.messages().stream().map(ProviderMessage::role).toList());
    assertEquals(1, sameEnvironment.messages().get(1).contents().size());

    ProviderRequest switchedEnvironment =
        MATERIALIZER.materialize(path, liveSpec(environmentBinding("prod")));
    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.USER),
        switchedEnvironment.messages().stream().map(ProviderMessage::role).toList());
    assertEquals(
        "Previous context:\nread a.txt:\n```\nfile body\n```",
        textOf(switchedEnvironment.messages().get(1)));
  }

  private static EntryPath toolHistoryPath() {
    List<Entry> entries = new ArrayList<>();
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    entries.add(entry(2, 1, resolvedStart(TurnStartReason.INPUT, null)));
    entries.add(entry(3, 2, user("go")));
    entries.add(
        entry(
            4,
            3,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent(
                            "call-1",
                            "fs_read",
                            "fs.read",
                            "{\"path\":\"a.txt\"}",
                            "read a.txt",
                            "dev"))),
                new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE, new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L)),
                null)));
    entries.add(
        entry(
            5,
            4,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "fs_read",
                            "fs.read",
                            List.of(new TextMessageContent("file body")),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    id(44L), id(4L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null, null))));
    return new EntryPath(entries);
  }

  /** 冻结了 Environment 名的 fs_read binding：environmentName 是历史投影判定 native 资格的 durable 事实。 */
  private static ToolBinding environmentBinding(String environmentName) {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "fs_read",
                "read a file",
                "fs.read",
                new InputSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "fs.read", List.of()),
        EnvironmentSupport.REQUIRED,
        new EnvironmentId(id(9L)),
        environmentName);
  }

  private static ProviderReplayState sampleReplayState() {
    return new ProviderReplayState(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        new ProviderReplayAffinity(
            ProviderType.ANTHROPIC, "anthropic", UUID.randomUUID(), "claude-3-5-sonnet"),
        JsonNodeFactory.instance.objectNode().put("k", "v"));
  }

  private static EntryPath conversationPath(int closedTurns) {
    List<Entry> entries = new ArrayList<>();
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    long nextId = 2L;
    UUID parent = id(1L);
    for (int i = 1; i <= closedTurns; i++) {
      UUID start = id(nextId++);
      entries.add(entry(start, parent, resolvedStart(TurnStartReason.INPUT, null)));
      UUID user = id(nextId++);
      entries.add(entry(user, start, user("user-" + i)));
      UUID assistant = id(nextId++);
      entries.add(entry(assistant, user, assistant("reply-" + i)));
      UUID end = id(nextId++);
      entries.add(
          entry(
              end,
              assistant,
              new TurnEndPayload(start, TurnEndOutcome.COMPLETED, false, null, null)));
      parent = end;
    }
    return new EntryPath(entries);
  }

  /**
   * Resolver 只把 systemInstruction/bindings 冻进 spec；path 上的普通历史不得进入编码。参数保留以证明调用方即使看到长 path 也不会把它写进
   * spec。
   */
  private static ModelRequestSpec specFromFrozenInputs(EntryPath path, ToolBinding binding) {
    Objects.requireNonNull(path, "path");
    return liveSpec(binding);
  }

  private static ModelRequestSpec liveSpec(ToolBinding binding) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        descriptor(),
        variant(),
        1024,
        "Test system instruction.",
        List.of(binding),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelRequestSpec compactionSpec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        descriptor(),
        variant(),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /**
   * Goal 路径：ROOT(无 Goal) -> INPUT(设置 Goal，TURN_START 冻结 settings 含 Goal) + 冻结 Goal USER 消息 ->
   * assistant -> TURN_END -> [可选清除 turn] -> COMPACTION（TURN_START 冻结其自身 settings 中的
   * Goal，是背景的唯一来源）。{@code cutGoalInput} 为 true 时保留区间从 Goal 输入消息之后开始（消息被切掉）。
   */
  private static EntryPath goalHistoryPath(boolean cutGoalInput, boolean cleared) {
    List<Entry> entries = new ArrayList<>();
    BranchSettings goalSettings = goalSettings("ship it");
    entries.add(entry(1, 0, new RootPayload(SETTINGS)));
    entries.add(
        entry(
            2,
            1,
            new TurnStartPayload(TurnStartReason.INPUT, goalSettings, OWNER, 4096, 1024, null)));
    entries.add(entry(3, 2, new MessagePayload(GoalMessages.inputSet("ship it"), null, null)));
    entries.add(entry(4, 3, assistant("first reply")));
    entries.add(
        entry(5, 4, new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, false, null, null)));
    long cutId = cutGoalInput ? 4L : 3L;
    long compactionTurnStart = 6L;
    BranchSettings boundarySettings = goalSettings;
    UUID parent = id(5L);
    if (cleared) {
      BranchSettings clearedSettings = SETTINGS.withGoal(null);
      entries.add(
          entry(
              6,
              5,
              new TurnStartPayload(
                  TurnStartReason.INPUT, clearedSettings, OWNER, 4096, 1024, null)));
      entries.add(entry(7, 6, new MessagePayload(GoalMessages.inputCleared(), null, null)));
      entries.add(entry(8, 7, assistant("cleared reply")));
      entries.add(
          entry(9, 8, new TurnEndPayload(id(6L), TurnEndOutcome.COMPLETED, false, null, null)));
      // 清除后的输入消息同样已被切掉；压缩边界冻结的是无 Goal 的设置。
      cutId = 8L;
      parent = id(9L);
      compactionTurnStart = 10L;
      boundarySettings = clearedSettings;
    }
    CompactionStart compaction =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, id(cutId), null, null);
    entries.add(
        entry(
            id(compactionTurnStart),
            parent,
            new TurnStartPayload(
                TurnStartReason.COMPACTION, boundarySettings, OWNER, 4096, 1024, compaction)));
    entries.add(
        entry(
            id(compactionTurnStart + 1),
            id(compactionTurnStart),
            new CompactionPayload("kept summary", null)));
    entries.add(
        entry(
            id(compactionTurnStart + 2),
            id(compactionTurnStart + 1),
            new TurnEndPayload(
                id(compactionTurnStart), TurnEndOutcome.COMPLETED, false, null, null)));
    return new EntryPath(entries);
  }

  /** 在压缩边界冻结 Goal 的路径之后追加一个真实清除 turn：压缩（及其冻结 Goal 背景）不变，清除以显式 USER 输入消息出现在真实近期消息末尾。 */
  private static EntryPath goalThenClearAfterCompactionPath() {
    List<Entry> entries = new ArrayList<>(goalHistoryPath(true, false).entries());
    UUID parent = entries.get(entries.size() - 1).id();
    entries.add(
        entry(
            id(20L),
            parent,
            new TurnStartPayload(
                TurnStartReason.INPUT, SETTINGS.withGoal(null), OWNER, 4096, 1024, null)));
    entries.add(
        entry(id(21L), id(20L), new MessagePayload(GoalMessages.inputCleared(), null, null)));
    entries.add(entry(id(22L), id(21L), assistant("cleared reply")));
    entries.add(
        entry(
            id(23L),
            id(22L),
            new TurnEndPayload(id(20L), TurnEndOutcome.COMPLETED, false, null, null)));
    return new EntryPath(entries);
  }

  /** AgentMessage 的纯文本拼接，用于与投影后的 ProviderMessage 文本比较。 */
  private static String agentText(AgentMessage message) {
    StringBuilder text = new StringBuilder();
    for (var content : message.contents()) {
      if (content instanceof TextMessageContent value) {
        text.append(value.text());
      }
    }
    return text.toString();
  }

  private static BranchSettings goalSettings(String text) {
    return SETTINGS.withGoal(new GoalSetting(id(700L), text));
  }

  private static TurnStartPayload resolvedStart(
      TurnStartReason reason, CompactionStart compaction) {
    return new TurnStartPayload(reason, SETTINGS, OWNER, 4096, 1024, compaction);
  }

  private static ToolBinding bashBinding() {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "bash",
                "run bash",
                "bash",
                new InputSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ModelDescriptor descriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ModelVariant variant() {
    return new ModelVariant("v1");
  }

  private static Entry entry(long id, long parent, EntryPayload payload) {
    return new Entry(id(id), id(100L), parent == 0L ? null : id(parent), payload, T0);
  }

  private static Entry entry(UUID id, UUID parent, EntryPayload payload) {
    return new Entry(id, id(100L), parent, payload, T0);
  }

  private static MessagePayload user(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))), null, null);
  }

  private static MessagePayload assistant(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L)),
        null);
  }

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (var block : message.contents()) {
      text.append(((ProviderTextBlock) block).text());
    }
    return text.toString();
  }
}
