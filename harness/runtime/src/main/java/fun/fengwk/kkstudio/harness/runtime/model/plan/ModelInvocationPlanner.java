package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从完整 root-to-head Entry path 派生当前 response debt 对应的冻结 ModelInvocation plan。
 *
 * <p>planner 只读取传入值对象。发现 debt 后，请求只使用截至 debt Entry 的路径前缀；head 与 debt 之间的配置或系统消息不能 反向改变该 response
 * boundary。返回 plan 仍以实际 head 作为 source identity，由 Reconciler 在事务中创建 Invocation。
 */
public final class ModelInvocationPlanner {

  private final ProviderMessageProjector messageProjector;
  private final ToolDescriptorJsonCodec toolDescriptorCodec;
  private final PromptCacheAffinityKeyFactory cacheKeyFactory;

  public ModelInvocationPlanner() {
    this(
        new ProviderMessageProjector(),
        new ToolDescriptorJsonCodec(),
        new PromptCacheAffinityKeyFactory());
  }

  ModelInvocationPlanner(
      ProviderMessageProjector messageProjector,
      ToolDescriptorJsonCodec toolDescriptorCodec,
      PromptCacheAffinityKeyFactory cacheKeyFactory) {
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
    this.toolDescriptorCodec = Objects.requireNonNull(toolDescriptorCodec, "toolDescriptorCodec");
    this.cacheKeyFactory = Objects.requireNonNull(cacheKeyFactory, "cacheKeyFactory");
  }

  /**
   * 派生当前 head 的 ModelInvocation plan。
   *
   * @return 没有 response debt 或最近响应已由 Assistant/AssistantError 终结时返回 empty
   */
  public Optional<ModelInvocationPlan> plan(
      long sessionId, long sourceHeadEntryId, List<SessionEntry> rootToHead) {
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (sourceHeadEntryId <= 0) {
      throw new IllegalArgumentException("sourceHeadEntryId must be positive");
    }
    List<SessionEntry> path = validatePath(sourceHeadEntryId, rootToHead);
    int debtIndex = findDebtIndex(path);
    if (debtIndex < 0) {
      return Optional.empty();
    }

    List<SessionEntry> debtPrefix = List.copyOf(path.subList(0, debtIndex + 1));
    RuntimeConfigSnapshot config = latestConfig(debtPrefix);
    List<AgentMessage> semanticMessages = projectSemanticMessages(debtPrefix, config);
    List<ProviderMessage> providerMessages = messageProjector.project(semanticMessages);
    List<ProviderToolDefinition> providerTools = providerTools(config);
    ProviderRequest baseRequest =
        new ProviderRequest(
            config.model().descriptor(),
            config.model().variant(),
            providerMessages,
            providerTools,
            ProviderCacheControl.none());
    ProviderRequest request =
        new PromptCacheRequestFinalizer(sessionId, cacheKeyFactory).apply(baseRequest);
    return Optional.of(new ModelInvocationPlan(sourceHeadEntryId, request, config));
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
          case RUNTIME_CONFIG -> payload instanceof RuntimeConfigSnapshot;
          case MESSAGE -> payload instanceof MessageEntryPayload;
          case CUSTOM_MESSAGE -> payload instanceof CustomMessageEntryPayload;
          case ASSISTANT_ERROR -> payload instanceof AssistantErrorEntryPayload;
        };
    if (!supported) {
      throw new IllegalArgumentException(
          "unsupported payload implementation for "
              + payload.type()
              + ": "
              + payload.getClass().getName());
    }
  }

  private static int findDebtIndex(List<SessionEntry> path) {
    for (int index = path.size() - 1; index >= 0; index--) {
      EntryPayload payload = path.get(index).payload();
      if (payload instanceof AssistantErrorEntryPayload) {
        return -1;
      }
      AgentMessage message = message(payload);
      if (message == null || message.role() == AgentMessageRole.SYSTEM) {
        continue;
      }
      if (message.role() == AgentMessageRole.ASSISTANT) {
        return -1;
      }
      if (message.role() == AgentMessageRole.USER || message.role() == AgentMessageRole.TOOL) {
        return index;
      }
    }
    return -1;
  }

  private static AgentMessage message(EntryPayload payload) {
    if (payload instanceof MessageEntryPayload message) {
      return message.message();
    }
    if (payload instanceof CustomMessageEntryPayload message) {
      return message.message();
    }
    return null;
  }

  private static RuntimeConfigSnapshot latestConfig(List<SessionEntry> debtPrefix) {
    for (int index = debtPrefix.size() - 1; index >= 0; index--) {
      if (debtPrefix.get(index).payload() instanceof RuntimeConfigSnapshot config) {
        return config;
      }
    }
    throw new IllegalArgumentException("response debt requires an earlier RUNTIME_CONFIG entry");
  }

  private static List<AgentMessage> projectSemanticMessages(
      List<SessionEntry> debtPrefix, RuntimeConfigSnapshot config) {
    List<AgentMessage> messages = new ArrayList<>();
    String systemPrompt = composeSystemPrompt(config);
    if (!systemPrompt.isBlank()) {
      messages.add(AgentMessage.system(systemPrompt));
    }
    for (SessionEntry entry : debtPrefix) {
      EntryPayload payload = entry.payload();
      if (payload instanceof MessageEntryPayload message) {
        messages.add(message.message());
      } else if (payload instanceof CustomMessageEntryPayload message) {
        messages.add(message.message());
      }
    }
    return List.copyOf(messages);
  }

  private List<ProviderToolDefinition> providerTools(RuntimeConfigSnapshot config) {
    return config.tools().stream()
        .map(
            binding ->
                new ProviderToolDefinition(
                    binding.descriptor().name(),
                    binding.descriptor().description(),
                    toolDescriptorCodec.encodeInputSchema(binding.descriptor().inputSchema())))
        .toList();
  }

  private static String composeSystemPrompt(RuntimeConfigSnapshot config) {
    String base = config.agent().systemPrompt();
    if (config.skills().isEmpty()) {
      return base;
    }
    StringBuilder section = new StringBuilder();
    section.append("\n\n");
    section.append("The following skills provide specialized instructions for specific tasks.\n");
    section.append(
        "Use a skill by its exact name from <available_skills> when the task matches its description.\n");
    section.append("\n<available_skills>\n");
    for (SkillSnapshot skill : config.skills()) {
      section.append("  <skill>\n");
      section.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
      section
          .append("    <description>")
          .append(escapeXml(skill.description()))
          .append("</description>\n");
      section.append("  </skill>\n");
    }
    section.append("</available_skills>");
    return base.isBlank() ? section.toString().stripLeading() : base + section;
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
