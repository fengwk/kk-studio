package fun.fengwk.kkstudio.harness.runtime.model.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.EnvironmentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ExecutionPolicySnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSummaryEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.LabelEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ModelInvocationPlannerTest {

  private static final long SESSION_ID = 41L;
  private static final ModelInvocationPlanner PLANNER = new ModelInvocationPlanner();

  @Test
  void returnsEmptyWithoutDebtOrAfterTerminalResponseBarrier() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());

    assertTrue(PLANNER.plan(SESSION_ID, 2, path(new RootEntryPayload(), config)).isEmpty());
    assertTrue(
        PLANNER
            .plan(
                SESSION_ID,
                3,
                path(
                    new RootEntryPayload(),
                    config,
                    custom(new AgentMessage(AgentMessageRole.SYSTEM, text("context")))))
            .isEmpty());
    assertTrue(
        PLANNER
            .plan(
                SESSION_ID,
                4,
                path(
                    new RootEntryPayload(),
                    config,
                    message(user("question")),
                    custom(assistant("answer"))))
            .isEmpty());
    assertTrue(
        PLANNER
            .plan(
                SESSION_ID,
                4,
                path(
                    new RootEntryPayload(),
                    config,
                    message(user("question")),
                    new AssistantErrorEntryPayload(
                        new ModelInvocationError(
                            ProviderErrorKind.INVALID_REQUEST, "bad request"))))
            .isEmpty());
  }

  @Test
  void plansUserToolAndCompactionDebt() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());

    ModelInvocationPlan userPlan =
        plan(path(new RootEntryPayload(), config, message(user("question"))));
    assertEquals(List.of(ProviderMessageRole.SYSTEM, ProviderMessageRole.USER), roles(userPlan));
    assertEquals("question", text(userPlan.request().messages().get(1)));

    ModelInvocationPlan toolPlan =
        plan(
            path(
                new RootEntryPayload(),
                config,
                custom(assistantToolCall("call-1")),
                custom(toolResult("call-1"))));
    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM, ProviderMessageRole.ASSISTANT, ProviderMessageRole.TOOL),
        roles(toolPlan));

    ModelInvocationPlan compactionPlan =
        plan(
            path(
                new RootEntryPayload(),
                config,
                message(user("kept")),
                new CompactionEntryPayload("summary", 3, 12, "{}")));
    assertEquals(
        List.of(ProviderMessageRole.SYSTEM, ProviderMessageRole.SYSTEM, ProviderMessageRole.USER),
        roles(compactionPlan));
    assertEquals("Session summary:\nsummary", text(compactionPlan.request().messages().get(1)));
  }

  @Test
  void debtBoundaryExcludesLaterConfigurationAndContextEntries() {
    RuntimeConfigSnapshot oldConfig =
        config("model-old", "old-system", List.of(), List.of(), disabled());
    RuntimeConfigSnapshot newConfig =
        config("model-new", "new-system", List.of(), List.of(), disabled());
    List<SessionEntry> path =
        path(
            new RootEntryPayload(),
            oldConfig,
            message(user("question")),
            newConfig,
            custom(new AgentMessage(AgentMessageRole.SYSTEM, text("late-system"))),
            new LabelEntryPayload("late-label"),
            new BranchSummaryEntryPayload("late-branch"));

    ModelInvocationPlan plan = PLANNER.plan(SESSION_ID, 7, path).orElseThrow();

    assertEquals(7, plan.sourceHeadEntryId());
    assertEquals(oldConfig, plan.configSnapshot());
    assertEquals("model-old", plan.request().model().modelId());
    assertEquals(List.of(ProviderMessageRole.SYSTEM, ProviderMessageRole.USER), roles(plan));
    assertEquals("old-system", text(plan.request().messages().get(0)));
    assertEquals("question", text(plan.request().messages().get(1)));
  }

  @Test
  void usesLatestEarlierConfigAndProjectsCanonicalSkillsToolsAndBranchSummary() {
    ToolDescriptor zeta = tool("zeta", "z input");
    ToolDescriptor alpha = tool("alpha", "a input");
    RuntimeConfigSnapshot first = config("model-first", "first", List.of(), List.of(), disabled());
    RuntimeConfigSnapshot latest =
        config(
            "model-latest",
            "base",
            List.of(
                new SkillSnapshot("z&", "quote \" and '", "env"),
                new SkillSnapshot("a<", "angle >", "env")),
            List.of(ToolBinding.of(zeta), ToolBinding.of(alpha)),
            disabled());

    ModelInvocationPlan plan =
        plan(
            path(
                new RootEntryPayload(),
                first,
                latest,
                new BranchSummaryEntryPayload("branch"),
                message(user("question"))));

    assertEquals("model-latest", plan.request().model().modelId());
    assertEquals(
        List.of(ProviderMessageRole.SYSTEM, ProviderMessageRole.SYSTEM, ProviderMessageRole.USER),
        roles(plan));
    String system = text(plan.request().messages().get(0));
    assertTrue(system.startsWith("base\n\nThe following skills"));
    assertTrue(system.indexOf("<name>a&lt;</name>") < system.indexOf("<name>z&amp;</name>"));
    assertTrue(system.contains("<description>angle &gt;</description>"));
    assertTrue(system.contains("<description>quote &quot; and &apos;</description>"));
    assertEquals("Branch summary:\nbranch", text(plan.request().messages().get(1)));
    assertEquals(
        List.of("alpha", "zeta"), plan.request().tools().stream().map(t -> t.name()).toList());
    assertEquals(
        new ToolDescriptorJsonCodec().encodeInputSchema(alpha.inputSchema()),
        plan.request().tools().get(0).inputSchemaJson());
  }

  @Test
  void appliesLastEffectiveCompactionRetainedRangeAndDropsInvalidCompactions() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());
    ModelInvocationPlan effective =
        plan(
            path(
                new RootEntryPayload(),
                config,
                message(user("discarded")),
                custom(assistant("discarded-answer")),
                message(user("kept")),
                new CompactionEntryPayload("summary", 5, 100, "{}"),
                custom(new AgentMessage(AgentMessageRole.SYSTEM, text("after"))),
                message(user("latest"))));

    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.USER,
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.USER),
        roles(effective));
    assertEquals("Session summary:\nsummary", text(effective.request().messages().get(1)));
    assertEquals("kept", text(effective.request().messages().get(2)));
    assertEquals("after", text(effective.request().messages().get(3)));
    assertEquals("latest", text(effective.request().messages().get(4)));

    ModelInvocationPlan invalid =
        plan(
            path(
                new RootEntryPayload(),
                config,
                message(user("original")),
                custom(assistant("answer")),
                new CompactionEntryPayload("invalid", 999, 100, "{}")));
    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM, ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT),
        roles(invalid));
    assertFalse(
        invalid.request().messages().stream()
            .map(ModelInvocationPlannerTest::text)
            .anyMatch(value -> value.contains("invalid")));
  }

  @Test
  void repairsOrphanToolCallBeforeDebtMessage() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());
    ModelInvocationPlan plan =
        plan(
            path(
                new RootEntryPayload(),
                config,
                custom(assistantToolCall("call-1")),
                message(user("continue"))));

    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL,
            ProviderMessageRole.USER),
        roles(plan));
    ProviderToolResultBlock result =
        assertInstanceOf(
            ProviderToolResultBlock.class, plan.request().messages().get(2).contents().get(0));
    assertTrue(result.error());
    assertEquals("No result provided", ((ProviderTextBlock) result.contents().get(0)).text());
  }

  @Test
  void finalizesDisabledAffinityAndBreakpointCachePolicies() {
    RuntimeConfigSnapshot disabledConfig =
        config("disabled", "system", List.of(), List.of(), disabled());
    ModelInvocationPlan disabledPlan =
        plan(path(new RootEntryPayload(), disabledConfig, message(user("question"))));
    assertEquals(PromptCacheRetention.NONE, disabledPlan.request().cacheControl().retention());
    assertEquals(null, disabledPlan.request().cacheControl().affinityKey());

    PromptCachePolicy affinity =
        PromptCachePolicy.affinityShort(
            PromptCacheCapability.affinity(EnumSet.of(PromptCacheRetention.SHORT)));
    RuntimeConfigSnapshot affinityConfig = config("affinity", "", List.of(), List.of(), affinity);
    ModelInvocationPlan affinityPlan =
        plan(path(new RootEntryPayload(), affinityConfig, message(user("question"))));
    assertEquals(PromptCacheRetention.SHORT, affinityPlan.request().cacheControl().retention());
    assertTrue(affinityPlan.request().cacheControl().affinityKey().startsWith("pc1-"));
    ModelInvocationPlan otherSessionPlan =
        PLANNER
            .plan(
                42, 3, path(42, new RootEntryPayload(), affinityConfig, message(user("question"))))
            .orElseThrow();
    assertNotEquals(
        affinityPlan.request().cacheControl().affinityKey(),
        otherSessionPlan.request().cacheControl().affinityKey());

    PromptCachePolicy breakpoints =
        PromptCachePolicy.breakpointsShort(
            PromptCacheCapability.breakpoints(
                EnumSet.of(PromptCacheRetention.SHORT),
                EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)));
    RuntimeConfigSnapshot breakpointConfig =
        config(
            "breakpoints",
            "system",
            List.of(),
            List.of(ToolBinding.of(tool("lookup", "query"))),
            breakpoints);
    ModelInvocationPlan breakpointPlan =
        plan(path(new RootEntryPayload(), breakpointConfig, message(user("question"))));
    assertEquals(
        EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        breakpointPlan.request().cacheControl().breakpoints());
  }

  @Test
  void rejectsMissingConfigAndMalformedPaths() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());
    List<SessionEntry> valid = path(new RootEntryPayload(), config, message(user("question")));

    assertThrows(
        IllegalArgumentException.class,
        () -> PLANNER.plan(SESSION_ID, 2, path(new RootEntryPayload(), message(user("question")))));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(0, 3, valid));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 0, valid));
    assertThrows(NullPointerException.class, () -> PLANNER.plan(SESSION_ID, 3, null));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 1, List.of()));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 2, valid));

    List<SessionEntry> wrongFirst = List.of(valid.get(1), valid.get(2));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 3, wrongFirst));

    List<SessionEntry> crossSession = new ArrayList<>(valid);
    crossSession.set(2, entry(3, 99, 2L, message(user("question"))));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 3, crossSession));

    List<SessionEntry> broken = new ArrayList<>(valid);
    broken.set(2, entry(3, SESSION_ID, 1L, message(user("question"))));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 3, broken));

    List<SessionEntry> duplicate = new ArrayList<>(valid);
    duplicate.set(2, entry(2, SESSION_ID, 2L, message(user("question"))));
    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 2, duplicate));
  }

  @Test
  void rejectsUnknownPayloadImplementation() {
    List<SessionEntry> path =
        path(
            new RootEntryPayload(),
            config("model-a", "system", List.of(), List.of(), disabled()),
            new UnknownMessagePayload());

    assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(SESSION_ID, 3, path));
  }

  @Test
  void doesNotMutateCallerPath() {
    RuntimeConfigSnapshot config = config("model-a", "system", List.of(), List.of(), disabled());
    ArrayList<SessionEntry> mutable =
        new ArrayList<>(path(new RootEntryPayload(), config, message(user("question"))));
    List<SessionEntry> before = List.copyOf(mutable);

    ModelInvocationPlan plan = PLANNER.plan(SESSION_ID, 3, mutable).orElseThrow();

    assertEquals(before, mutable);
    mutable.clear();
    assertEquals("question", text(plan.request().messages().get(1)));
  }

  private static ModelInvocationPlan plan(List<SessionEntry> path) {
    return PLANNER.plan(SESSION_ID, path.get(path.size() - 1).id(), path).orElseThrow();
  }

  private static List<ProviderMessageRole> roles(ModelInvocationPlan plan) {
    return plan.request().messages().stream().map(ProviderMessage::role).toList();
  }

  private static String text(ProviderMessage message) {
    return ((ProviderTextBlock) message.contents().get(0)).text();
  }

  private static MessageEntryPayload message(AgentMessage message) {
    return new MessageEntryPayload(message);
  }

  private static CustomMessageEntryPayload custom(AgentMessage message) {
    return new CustomMessageEntryPayload(message);
  }

  private static AgentMessage user(String value) {
    return new AgentMessage(AgentMessageRole.USER, text(value));
  }

  private static AgentMessage assistant(String value) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, text(value));
  }

  private static AgentMessage assistantToolCall(String callId) {
    return new AgentMessage(
        AgentMessageRole.ASSISTANT, List.of(new ToolCallMessageContent(callId, "lookup", "{}")));
  }

  private static AgentMessage toolResult(String callId) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                callId, "lookup", List.of(new TextMessageContent("result")), false, "{}")));
  }

  private static List<AgentMessageContent> text(String value) {
    return List.of(new TextMessageContent(value));
  }

  private static List<SessionEntry> path(EntryPayload... payloads) {
    return path(SESSION_ID, payloads);
  }

  private static List<SessionEntry> path(long sessionId, EntryPayload... payloads) {
    List<SessionEntry> entries = new ArrayList<>(payloads.length);
    for (int index = 0; index < payloads.length; index++) {
      long id = index + 1L;
      entries.add(entry(id, sessionId, index == 0 ? null : id - 1, payloads[index]));
    }
    return List.copyOf(entries);
  }

  private static SessionEntry entry(long id, long sessionId, Long parentId, EntryPayload payload) {
    return new SessionEntry(
        id, sessionId, parentId, payload.type(), payload, Instant.EPOCH.plusSeconds(id));
  }

  private static RuntimeConfigSnapshot config(
      String modelId,
      String systemPrompt,
      List<SkillSnapshot> skills,
      List<ToolBinding> tools,
      PromptCachePolicy promptCachePolicy) {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            11,
            12,
            ProviderType.OPENAI,
            modelId,
            modelId,
            100_000,
            8_000,
            EnumSet.of(ModelInputModality.TEXT),
            true,
            false,
            List.of(variant),
            zeroPricing(),
            promptCachePolicy);
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(10, "agent", systemPrompt),
        new ModelSnapshot(descriptor, variant),
        tools,
        skills,
        new ExecutionPolicySnapshot(20, 4, 2, null, List.of(), false),
        new EnvironmentSnapshot("env", "workspace"));
  }

  private static ModelPricing zeroPricing() {
    return new ModelPricing(
        "USD",
        "default",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ToolDescriptor tool(String name, String parameterDescription) {
    return new ToolDescriptor(
        name,
        "v1",
        name + " description",
        name,
        new ToolParamsSchema(
            "parameters",
            Map.of("query", new ToolStringSchema(parameterDescription)),
            Set.of("query"),
            false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }

  private static PromptCachePolicy disabled() {
    return PromptCachePolicy.disabled();
  }

  private record UnknownMessagePayload() implements EntryPayload {
    @Override
    public EntryType type() {
      return EntryType.MESSAGE;
    }
  }
}
