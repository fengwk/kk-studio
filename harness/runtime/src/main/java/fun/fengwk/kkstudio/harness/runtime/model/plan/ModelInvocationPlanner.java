package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Derives one immutable provider request from a root-to-head semantic path.
 *
 * <p>The planner stores no live configuration. It locates the originating name-only turn reference,
 * resolves that reference for every plan call, and freezes the resulting provider request, tool
 * bindings, skill bindings, and YOLO flag into the durable invocation request.
 */
public final class ModelInvocationPlanner {

  private final ProviderMessageProjector messageProjector;
  private final ToolDescriptorJsonCodec toolDescriptorCodec;
  private final PromptCacheAffinityKeyFactory cacheKeyFactory;
  private final TurnExecutionResolver executionResolver;
  private final ResponseDebtDetector debtDetector;

  public ModelInvocationPlanner(TurnExecutionResolver executionResolver) {
    this(
        new ProviderMessageProjector(),
        new ToolDescriptorJsonCodec(),
        new PromptCacheAffinityKeyFactory(),
        executionResolver,
        new ResponseDebtDetector());
  }

  ModelInvocationPlanner(
      ProviderMessageProjector messageProjector,
      ToolDescriptorJsonCodec toolDescriptorCodec,
      PromptCacheAffinityKeyFactory cacheKeyFactory,
      TurnExecutionResolver executionResolver) {
    this(
        messageProjector,
        toolDescriptorCodec,
        cacheKeyFactory,
        executionResolver,
        new ResponseDebtDetector());
  }

  ModelInvocationPlanner(
      ProviderMessageProjector messageProjector,
      ToolDescriptorJsonCodec toolDescriptorCodec,
      PromptCacheAffinityKeyFactory cacheKeyFactory,
      TurnExecutionResolver executionResolver,
      ResponseDebtDetector debtDetector) {
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
    this.toolDescriptorCodec = Objects.requireNonNull(toolDescriptorCodec, "toolDescriptorCodec");
    this.cacheKeyFactory = Objects.requireNonNull(cacheKeyFactory, "cacheKeyFactory");
    this.executionResolver = Objects.requireNonNull(executionResolver, "executionResolver");
    this.debtDetector = Objects.requireNonNull(debtDetector, "debtDetector");
  }

  /**
   * Plans the current head.
   *
   * <p>{@link PlanningResult.Failed} is a durable-facing terminal planning outcome. It is not
   * represented by a fake provider request and lets the transaction adapter append an {@code
   * ASSISTANT_ERROR} barrier.
   */
  public PlanningResult plan(
      long sessionId, long sourceHeadEntryId, List<SessionEntry> rootToHead) {
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (sourceHeadEntryId <= 0) {
      throw new IllegalArgumentException("sourceHeadEntryId must be positive");
    }
    List<SessionEntry> path = validatePath(sourceHeadEntryId, rootToHead);
    OptionalInt debtIndex = debtDetector.findDebtIndex(path);
    if (debtIndex.isEmpty()) {
      return new PlanningResult.NoDebt();
    }

    List<SessionEntry> debtPrefix = List.copyOf(path.subList(0, debtIndex.getAsInt() + 1));
    Optional<TurnSettings> settings = nearestTurnSettings(debtPrefix);
    if (settings.isEmpty()) {
      return new PlanningResult.Failed(
          new PlanningFailure(
              PlanningFailureKind.MISSING_TURN_SETTINGS,
              "response debt has no originating turn settings"));
    }

    TurnExecutionResolver.Resolution resolution =
        Objects.requireNonNull(executionResolver.resolve(settings.get()), "resolver resolution");
    if (resolution instanceof TurnExecutionResolver.Resolution.Failed failed) {
      return new PlanningResult.Failed(failed.failure());
    }
    ResolvedTurnExecution execution =
        ((TurnExecutionResolver.Resolution.Resolved) resolution).execution();

    List<AgentMessage> semanticMessages = projectSemanticMessages(debtPrefix, execution);
    List<ProviderMessage> providerMessages = messageProjector.project(semanticMessages);
    List<ProviderToolDefinition> providerTools = providerTools(execution.toolBindings());
    ProviderRequest baseRequest =
        new ProviderRequest(
            execution.model(),
            execution.variant(),
            providerMessages,
            providerTools,
            ProviderCacheControl.none());
    ProviderRequest request =
        new PromptCacheRequestFinalizer(sessionId, cacheKeyFactory).apply(baseRequest);
    ModelInvocationRequest invocationRequest =
        new ModelInvocationRequest(
            request, execution.toolBindings(), execution.skillBindings(), execution.yoloEnabled());
    return new PlanningResult.Planned(
        new ModelInvocationPlan(sourceHeadEntryId, invocationRequest));
  }

  private static List<SessionEntry> validatePath(
      long sourceHeadEntryId, List<SessionEntry> rootToHead) {
    Objects.requireNonNull(rootToHead, "rootToHead");
    if (rootToHead.isEmpty()) {
      throw new IllegalArgumentException("rootToHead must not be empty");
    }
    List<SessionEntry> path = List.copyOf(rootToHead);
    Set<Long> entryIds = new HashSet<>();
    SessionEntry previous = null;
    for (int index = 0; index < path.size(); index++) {
      SessionEntry entry = Objects.requireNonNull(path.get(index), "rootToHead[]");
      if (!entryIds.add(entry.id())) {
        throw new IllegalArgumentException("rootToHead must not contain duplicate entry ids");
      }
      requireSupportedPayload(entry);
      if (index == 0) {
        if (entry.payload().type() != EntryType.ROOT || entry.parentEntryId() != null) {
          throw new IllegalArgumentException("rootToHead must start with ROOT without a parent");
        }
      } else if (!Objects.equals(entry.parentEntryId(), previous.id())) {
        throw new IllegalArgumentException("rootToHead parent chain must be contiguous");
      }
      previous = entry;
    }
    if (previous.id() != sourceHeadEntryId) {
      throw new IllegalArgumentException("sourceHeadEntryId must equal the last path entry id");
    }
    return path;
  }

  private static void requireSupportedPayload(SessionEntry entry) {
    EntryPayload payload = entry.payload();
    boolean supported =
        switch (payload.type()) {
          case ROOT -> payload instanceof RootEntryPayload;
          case MESSAGE -> payload instanceof MessageEntryPayload;
          case CUSTOM_MESSAGE -> payload instanceof CustomMessageEntryPayload;
          case ASSISTANT_ERROR -> payload instanceof AssistantErrorEntryPayload;
          case ASSISTANT_ABORTED -> payload instanceof AssistantAbortedEntryPayload;
        };
    if (!supported) {
      throw new IllegalArgumentException(
          "unsupported payload implementation for "
              + payload.type()
              + ": "
              + payload.getClass().getName());
    }
  }

  private static Optional<TurnSettings> nearestTurnSettings(List<SessionEntry> debtPrefix) {
    for (int index = debtPrefix.size() - 1; index >= 0; index--) {
      EntryPayload payload = debtPrefix.get(index).payload();
      if (payload instanceof CustomMessageEntryPayload custom) {
        return Optional.of(custom.turnSettings());
      }
      if (payload instanceof MessageEntryPayload message
          && message.message().role() == AgentMessageRole.USER) {
        return Optional.of(message.turnSettings());
      }
    }
    return Optional.empty();
  }

  private static List<AgentMessage> projectSemanticMessages(
      List<SessionEntry> debtPrefix, ResolvedTurnExecution execution) {
    List<AgentMessage> messages = new ArrayList<>();
    String systemPrompt = composeSystemPrompt(execution.systemPrompt(), execution.skillBindings());
    if (!systemPrompt.isBlank()) {
      messages.add(AgentMessage.system(systemPrompt));
    }
    for (SessionEntry entry : debtPrefix) {
      EntryPayload payload = entry.payload();
      if (payload instanceof MessageEntryPayload message) {
        messages.add(message.message());
      } else if (payload instanceof CustomMessageEntryPayload message) {
        messages.add(message.message());
      } else if (payload instanceof AssistantAbortedEntryPayload message) {
        messages.add(message.message());
      }
    }
    return List.copyOf(messages);
  }

  private List<ProviderToolDefinition> providerTools(List<ToolBinding> bindings) {
    return bindings.stream()
        .map(
            binding ->
                new ProviderToolDefinition(
                    binding.descriptor().name(),
                    binding.descriptor().description(),
                    toolDescriptorCodec.encodeInputSchema(binding.descriptor().inputSchema())))
        .toList();
  }

  private static String composeSystemPrompt(String systemPrompt, List<SkillBinding> skillBindings) {
    if (skillBindings.isEmpty()) {
      return systemPrompt;
    }
    StringBuilder section = new StringBuilder();
    section.append("\n\n");
    section.append("The following skills provide specialized instructions for specific tasks.\n");
    section.append(
        "Use a skill by its exact name from <available_skills> when the task matches its description.\n");
    section.append("\n<available_skills>\n");
    for (SkillBinding skill : skillBindings) {
      section.append("  <skill>\n");
      section.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
      section
          .append("    <description>")
          .append(escapeXml(skill.description()))
          .append("</description>\n");
      section.append("  </skill>\n");
    }
    section.append("</available_skills>");
    return systemPrompt.isBlank() ? section.toString().stripLeading() : systemPrompt + section;
  }

  private static String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
