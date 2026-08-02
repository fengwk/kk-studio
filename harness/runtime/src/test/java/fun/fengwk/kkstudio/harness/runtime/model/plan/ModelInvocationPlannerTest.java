package fun.fengwk.kkstudio.harness.runtime.model.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure planner coverage for stateless turn resolution and durable request materialization. */
class ModelInvocationPlannerTest {

  private static final long SESSION_ID = 41L;
  private static final TurnSettings USER_SETTINGS =
      new TurnSettings("agent-user", "env-user", false);
  private static final TurnSettings CUSTOM_SETTINGS =
      new TurnSettings("agent-custom", "env-custom", true);

  @Test
  void callsLatestResolverForEverySeparatePlanCall() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(
                execution("model-first", "first", List.of(), List.of(), false),
                execution("model-second", "second", List.of(), List.of(), true)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);
    List<SessionEntry> path = path(new RootEntryPayload(), user("question", USER_SETTINGS));

    ModelInvocationPlan first = planned(planner.plan(SESSION_ID, 2L, path));
    ModelInvocationPlan second = planned(planner.plan(SESSION_ID, 2L, path));

    assertEquals(2, resolver.settings.size());
    assertEquals(USER_SETTINGS, resolver.settings.get(0));
    assertEquals(USER_SETTINGS, resolver.settings.get(1));
    assertEquals("model-first", first.request().providerRequest().model().modelName());
    assertEquals("model-second", second.request().providerRequest().model().modelName());
    assertNotEquals(first.request(), second.request());
  }

  @Test
  void selectsNearestUserOrCustomTurnSettings() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(
                execution("user-model", "user-system", List.of(), List.of(), false),
                execution("custom-model", "custom-system", List.of(), List.of(), true)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);

    planned(
        planner.plan(SESSION_ID, 2L, path(new RootEntryPayload(), user("nearest", USER_SETTINGS))));
    planned(
        planner.plan(
            SESSION_ID,
            4L,
            path(
                new RootEntryPayload(),
                user("first", USER_SETTINGS),
                custom(AgentMessageRole.SYSTEM, "context", CUSTOM_SETTINGS),
                toolResult("call-1"))));

    assertEquals(List.of(USER_SETTINGS, CUSTOM_SETTINGS), resolver.settings);
  }

  @Test
  void postToolResultDebtFindsTheOriginatingTurnSettings() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(execution("tool-model", "system", List.of(), List.of(), false)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);

    ModelInvocationPlan plan =
        planned(
            planner.plan(
                SESSION_ID,
                4L,
                path(
                    new RootEntryPayload(),
                    user("question", USER_SETTINGS),
                    assistantToolCall("call-1"),
                    toolResult("call-1"))));

    assertEquals(List.of(USER_SETTINGS), resolver.settings);
    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.USER,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL),
        roles(plan.request().providerRequest().messages()));
  }

  @Test
  void materializesResolvedPromptModelVariantToolsSkillsAndYoloExactly() {
    ToolBinding tool = ToolBinding.of(tool("lookup", "look up facts"), "environment-tools");
    SkillBinding skill = new SkillBinding("research", "Research facts", "environment-skills");
    ResolvedTurnExecution execution =
        execution("model-live", "resolved system", List.of(tool), List.of(skill), true);
    RecordingResolver resolver = new RecordingResolver(List.of(execution));

    ModelInvocationRequest request =
        planned(
                new ModelInvocationPlanner(resolver)
                    .plan(
                        SESSION_ID,
                        2L,
                        path(new RootEntryPayload(), user("question", USER_SETTINGS))))
            .request();

    assertEquals(execution.model(), request.providerRequest().model());
    assertEquals(execution.variant(), request.providerRequest().variant());
    assertEquals(execution.toolBindings(), request.toolBindings());
    assertEquals(execution.skillBindings(), request.skillBindings());
    assertTrue(request.yoloEnabled());
    String systemPrompt = text(request.providerRequest().messages().get(0));
    assertTrue(systemPrompt.startsWith("resolved system\n\nThe following skills"));
    assertTrue(systemPrompt.contains("<name>research</name>"));
    assertEquals(
        List.of(
            new ProviderToolDefinition(
                "lookup",
                "lookup description",
                new ToolDescriptorJsonCodec().encodeInputSchema(tool.descriptor().inputSchema()))),
        request.providerRequest().tools());
  }

  @Test
  void noDebtDoesNotCallResolver() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(execution("never-used", "never-used", List.of(), List.of(), false)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);

    assertInstanceOf(
        PlanningResult.NoDebt.class, planner.plan(SESSION_ID, 1L, path(new RootEntryPayload())));
    assertInstanceOf(
        PlanningResult.NoDebt.class,
        planner.plan(
            SESSION_ID,
            3L,
            path(new RootEntryPayload(), user("question", USER_SETTINGS), assistant("answer"))));
    assertInstanceOf(
        PlanningResult.NoDebt.class,
        planner.plan(
            SESSION_ID,
            3L,
            path(
                new RootEntryPayload(),
                user("question", USER_SETTINGS),
                new AssistantErrorEntryPayload(
                    new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad request")))));
    assertInstanceOf(
        PlanningResult.NoDebt.class,
        planner.plan(
            SESSION_ID,
            3L,
            path(
                new RootEntryPayload(),
                user("question", USER_SETTINGS),
                AssistantAbortedEntryPayload.ofTextAndThinking("partial", ""))));

    assertEquals(List.of(), resolver.settings);
  }

  @Test
  void debtDetectionDoesNotResolveLiveDefinitions() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(execution("never-used", "never-used", List.of(), List.of(), false)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);

    assertTrue(
        planner.hasResponseDebt(2L, path(new RootEntryPayload(), user("question", USER_SETTINGS))));
    assertTrue(!planner.hasResponseDebt(1L, path(new RootEntryPayload())));
    assertEquals(List.of(), resolver.settings);
  }

  @Test
  void resolverFailureIsTypedPlanningFailure() {
    PlanningFailure failure =
        new PlanningFailure(PlanningFailureKind.MODEL_NOT_FOUND, "model was deleted");
    RecordingResolver resolver = new RecordingResolver(failure);

    PlanningResult.Failed result =
        assertInstanceOf(
            PlanningResult.Failed.class,
            new ModelInvocationPlanner(resolver)
                .plan(
                    SESSION_ID, 2L, path(new RootEntryPayload(), user("question", USER_SETTINGS))));

    assertEquals(failure, result.failure());
  }

  @Test
  void missingTurnSettingsIsTypedFailure() {
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(execution("never-used", "never-used", List.of(), List.of(), false)));

    PlanningResult.Failed result =
        assertInstanceOf(
            PlanningResult.Failed.class,
            new ModelInvocationPlanner(resolver)
                .plan(SESSION_ID, 2L, path(new RootEntryPayload(), toolResult("call-1"))));

    assertEquals(PlanningFailureKind.MISSING_TURN_SETTINGS, result.failure().kind());
    assertEquals(List.of(), resolver.settings);
  }

  @Test
  void semanticHistoryAndSkillXmlEscapingRemainCorrect() {
    SkillBinding ampersand = new SkillBinding("z&", "quote \" and '", "env");
    SkillBinding lessThan = new SkillBinding("a<", "angle >", "env");
    RecordingResolver resolver =
        new RecordingResolver(
            List.of(
                execution(
                    "semantic-model", "base", List.of(), List.of(ampersand, lessThan), false)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);

    ModelInvocationPlan plan =
        planned(
            planner.plan(
                SESSION_ID,
                6L,
                path(
                    new RootEntryPayload(),
                    custom(AgentMessageRole.SYSTEM, "late context", CUSTOM_SETTINGS),
                    user("question", USER_SETTINGS),
                    assistantToolCall("call-1"),
                    toolResult("call-1"),
                    user("continue", USER_SETTINGS))));

    assertEquals(
        List.of(
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.SYSTEM,
            ProviderMessageRole.USER,
            ProviderMessageRole.ASSISTANT,
            ProviderMessageRole.TOOL,
            ProviderMessageRole.USER),
        roles(plan.request().providerRequest().messages()));
    String system = text(plan.request().providerRequest().messages().get(0));
    assertTrue(system.startsWith("base\n\nThe following skills"));
    assertTrue(system.contains("<name>z&amp;</name>"));
    assertTrue(system.contains("<name>a&lt;</name>"));
    assertTrue(system.contains("<description>quote &quot; and &apos;</description>"));
    assertTrue(system.contains("<description>angle &gt;</description>"));
    assertEquals("late context", text(plan.request().providerRequest().messages().get(1)));
    assertEquals("question", text(plan.request().providerRequest().messages().get(2)));
    assertEquals("continue", text(plan.request().providerRequest().messages().get(5)));
  }

  @Test
  void validatesPathAndDoesNotMutateCallerList() {
    RecordingResolver resolver =
        new RecordingResolver(List.of(execution("model", "system", List.of(), List.of(), false)));
    ModelInvocationPlanner planner = new ModelInvocationPlanner(resolver);
    ArrayList<SessionEntry> mutable =
        new ArrayList<>(path(new RootEntryPayload(), user("question", USER_SETTINGS)));
    List<SessionEntry> before = List.copyOf(mutable);

    ModelInvocationPlan plan = planned(planner.plan(SESSION_ID, 2L, mutable));

    assertEquals(before, mutable);
    mutable.clear();
    assertEquals("question", text(plan.request().providerRequest().messages().get(1)));
    assertThrows(IllegalArgumentException.class, () -> planner.plan(0L, 2L, before));
    assertThrows(IllegalArgumentException.class, () -> planner.plan(SESSION_ID, 1L, before));
    assertThrows(NullPointerException.class, () -> planner.plan(SESSION_ID, 2L, null));
  }

  private static ModelInvocationPlan planned(PlanningResult result) {
    return assertInstanceOf(PlanningResult.Planned.class, result).plan();
  }

  private static ResolvedTurnExecution execution(
      String modelName,
      String systemPrompt,
      List<ToolBinding> tools,
      List<SkillBinding> skills,
      boolean yoloEnabled) {
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            modelName,
            ProviderType.OPENAI,
            !tools.isEmpty() || !skills.isEmpty(),
            true,
            zeroPricing(),
            PromptCachePolicy.disabled());
    ModelVariant variant =
        new ModelVariant("variant", 512, 0.2, 0.9, null, null, null, List.of(), "medium");
    return new ResolvedTurnExecution(systemPrompt, model, variant, tools, skills, yoloEnabled);
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

  private static List<ProviderMessageRole> roles(List<ProviderMessage> messages) {
    return messages.stream().map(ProviderMessage::role).toList();
  }

  private static String text(ProviderMessage message) {
    return ((ProviderTextBlock) message.contents().getFirst()).text();
  }

  private static MessageEntryPayload user(String content, TurnSettings settings) {
    return new MessageEntryPayload(message(AgentMessageRole.USER, content), settings, null);
  }

  private static CustomMessageEntryPayload custom(
      AgentMessageRole role, String content, TurnSettings settings) {
    return new CustomMessageEntryPayload(message(role, content), settings);
  }

  private static MessageEntryPayload assistant(String content) {
    return new MessageEntryPayload(message(AgentMessageRole.ASSISTANT, content), null, metadata());
  }

  private static MessageEntryPayload assistantToolCall(String callId) {
    return new MessageEntryPayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent(callId, "lookup", "{}"))),
        null,
        toolCallMetadata());
  }

  private static MessageEntryPayload toolResult(String callId) {
    return new MessageEntryPayload(
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    callId, "lookup", List.of(new TextMessageContent("result")), false, "{}"))),
        null,
        null);
  }

  private static AgentMessage message(AgentMessageRole role, String content) {
    return new AgentMessage(role, List.of(new TextMessageContent(content)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.COMPLETED,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static AssistantMessageMetadata toolCallMetadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.TOOL_CALLS,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static List<SessionEntry> path(EntryPayload... payloads) {
    List<SessionEntry> entries = new ArrayList<>(payloads.length);
    for (int index = 0; index < payloads.length; index++) {
      long id = index + 1L;
      entries.add(new SessionEntry(id, index == 0 ? null : id - 1L, payloads[index]));
    }
    return List.copyOf(entries);
  }

  private static final class RecordingResolver implements TurnExecutionResolver {
    private final List<TurnSettings> settings = new ArrayList<>();
    private final List<ResolvedTurnExecution> executions;
    private final PlanningFailure failure;

    private RecordingResolver(List<ResolvedTurnExecution> executions) {
      this.executions = List.copyOf(executions);
      this.failure = null;
    }

    private RecordingResolver(PlanningFailure failure) {
      this.executions = List.of();
      this.failure = failure;
    }

    @Override
    public Resolution resolve(TurnSettings turnSettings) {
      settings.add(turnSettings);
      if (failure != null) {
        return new Resolution.Failed(failure);
      }
      int index = Math.min(settings.size() - 1, executions.size() - 1);
      return new Resolution.Resolved(executions.get(index));
    }
  }
}
