package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryInput;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
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
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
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
      new BranchSettings(null, "agent", new ModelSelection("provider", "model", "v1"));
  private static final ModelRequestMaterializer MATERIALIZER = new ModelRequestMaterializer();
  private static final ModelRequestSpecJsonCodec CODEC = new ModelRequestSpecJsonCodec();

  @Test
  void rematerializesTheSameLogicalRequestAfterCodecRoundTrip() {
    EntryPath path = conversationPath(2);
    ModelRequestSpec spec =
        liveSpec(List.of(AgentMessage.system("frozen preamble")), bashBinding());

    ProviderRequest first = MATERIALIZER.materialize(path, spec);
    ModelRequestSpec restored = CODEC.decode(CODEC.encode(spec));
    ProviderRequest second = MATERIALIZER.materialize(path, restored);

    assertEquals(first, second);
    assertEquals("frozen preamble", textOf(first.messages().get(0)));
    assertEquals("user-1", textOf(first.messages().get(1)));
    assertEquals("reply-1", textOf(first.messages().get(2)));
    assertEquals("user-2", textOf(first.messages().get(3)));
    assertEquals("reply-2", textOf(first.messages().get(4)));
    assertEquals(1, first.tools().size());
    assertEquals("bash", first.tools().get(0).name());
    assertEquals(
        new ToolDescriptorJsonCodec().encodeInputSchema(bashBinding().descriptor().inputSchema()),
        first.tools().get(0).inputSchemaJson());
  }

  @Test
  void encodedSpecSizeIsIndependentOfOrdinaryHistoryLength() {
    // 两条独立构造的等价 spec：模拟 resolver 只冻结 preamble/bindings，不把普通历史写入 durable spec。
    EntryPath shortPath = conversationPath(1);
    EntryPath longPath = conversationPath(32);
    ModelRequestSpec shortSpec =
        specFromFrozenInputs(shortPath, List.of(AgentMessage.system("sys")), bashBinding());
    ModelRequestSpec longSpec =
        specFromFrozenInputs(longPath, List.of(AgentMessage.system("sys")), bashBinding());
    String shortEncoded = CODEC.encode(shortSpec);
    String longEncoded = CODEC.encode(longSpec);

    ProviderRequest shortRequest = MATERIALIZER.materialize(shortPath, shortSpec);
    ProviderRequest longRequest = MATERIALIZER.materialize(longPath, longSpec);

    assertEquals(shortSpec, longSpec);
    assertEquals(shortEncoded, longEncoded);
    assertFalse(shortEncoded.contains("user-1"));
    assertFalse(longEncoded.contains("user-32"));
    assertFalse(longEncoded.contains("reply-32"));
    assertTrue(longRequest.messages().size() > shortRequest.messages().size());
    assertEquals(3, shortRequest.messages().size());
    assertEquals(65, longRequest.messages().size());
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
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(4L),
            null,
            null);
    entries.add(entry(6, 5, resolvedStart(TurnStartReason.COMPACTION, completed)));
    entries.add(entry(7, 6, new CompactionPayload("kept summary")));
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
        MATERIALIZER.materialize(
            new EntryPath(entries), liveSpec(List.of(AgentMessage.system("sys")), bashBinding()));

    assertEquals(5, request.messages().size());
    assertEquals("sys", textOf(request.messages().get(0)));
    assertEquals(
        CompactionPrompts.compactedContext("kept summary"), textOf(request.messages().get(1)));
    assertEquals("old-reply", textOf(request.messages().get(2)));
    assertEquals("kept-user", textOf(request.messages().get(3)));
    assertEquals("latest-user", textOf(request.messages().get(4)));
  }

  @Test
  void compactionRequestRebuildsTheSameSummaryPromptFromEntryIds() {
    EntryPath history = conversationPath(2);
    CompactionStart compaction =
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(4L),
            null,
            null);
    List<Entry> entries = new ArrayList<>(history.entries());
    entries.add(
        entry(id(10L), history.head().id(), resolvedStart(TurnStartReason.COMPACTION, compaction)));
    EntryPath path = new EntryPath(entries);
    CompactionSummaryInput input = CompactionPlanner.reconstructSummaryInput(path, compaction);
    ModelRequestSpec spec = compactionSpec();

    ProviderRequest request = MATERIALIZER.materialize(path, spec);

    assertEquals(2, request.messages().size());
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), textOf(request.messages().get(0)));
    assertEquals(
        CompactionPrompts.summaryUserPrompt(input.messages(), input.previousSummary()),
        textOf(request.messages().get(1)));
    assertTrue(request.tools().isEmpty());
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

  /** Resolver 只把 preamble/bindings 冻进 spec；path 上的普通历史不得进入编码。参数保留以证明调用方即使看到长 path 也不会把它写进 spec。 */
  private static ModelRequestSpec specFromFrozenInputs(
      EntryPath path, List<AgentMessage> preamble, ToolBinding binding) {
    Objects.requireNonNull(path, "path");
    return liveSpec(preamble, binding);
  }

  private static ModelRequestSpec liveSpec(List<AgentMessage> preamble, ToolBinding binding) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        descriptor(),
        variant(),
        preamble,
        List.of(binding),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelRequestSpec compactionSpec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        descriptor(),
        variant(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static TurnStartPayload resolvedStart(
      TurnStartReason reason, CompactionStart compaction) {
    return new TurnStartPayload(reason, SETTINGS, OWNER, 4096, 1024, compaction);
  }

  private static ToolBinding bashBinding() {
    return new ToolBinding(
        new AgentToolDefinition(
            new AgentToolId("test.bash"),
            new ToolDescriptor(
                "bash",
                "1.0",
                "run bash",
                "bash",
                new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE,
            AgentToolBackend.HOST),
        null,
        null);
  }

  private static ModelDescriptor descriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static ModelVariant variant() {
    return new ModelVariant("v1", null, null, null, null, null, null, List.of(), null);
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
            GenerationStopReason.COMPLETE,
            new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
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
