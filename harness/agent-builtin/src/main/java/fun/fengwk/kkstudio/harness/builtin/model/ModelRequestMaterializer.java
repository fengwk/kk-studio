package fun.fengwk.kkstudio.harness.builtin.model;

import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemHostedTool;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 唯一请求重建边界：从不可变 {@link EntryPath} 与冻结 {@link ModelRequestSpec} 纯投影内存 {@link ProviderRequest}。
 *
 * <p>不访问 catalog 或 Environment registry，也不持有事务。压缩执行不在本 materializer 内发生：COMPACTION 分支由 {@link
 * CompactionChildSpawn} 经统一 spawn 创建，首条输入是普通 CUSTOM_MESSAGE，父 COMPACTION turn 不创建
 * ModelInvocation。本类只把 durable 事实投影成请求：压缩后的主 Thread 以 摘要 wrapper 替代切点前历史，分支第一次请求在切点前最后一条 assistant
 * 之前写入唯一只读缓存断点。历史消息投影见 {@link ModelHistoryProjection}。
 *
 * <p>历史工具调用与结果按 durable Entry 投影，不根据当前工具绑定或 Environment 重新分类；没有存储结果的调用由历史正规化补齐 synthetic 结果。
 */
public final class ModelRequestMaterializer {

  private final SchemaJsonCodec schemaCodec;

  public ModelRequestMaterializer() {
    this(new SchemaJsonCodec());
  }

  ModelRequestMaterializer(SchemaJsonCodec schemaCodec) {
    this.schemaCodec = Objects.requireNonNull(schemaCodec, "schemaCodec");
  }

  /**
   * 纯投影一次 Model 请求：压缩执行的 spawning 与结算不在本类发生；分支首条输入由 {@code CompactionChildSpawn} 用共享 {@link
   * CompactionPrompts} 构造，父 COMPACTION turn 不产生 ModelInvocation。
   */
  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    SystemContext context = path.systemContext().orElse(null);
    boolean incrementalSystem =
        spec.providerType() == ProviderType.ANTHROPIC
            && spec.model().midConversationSystemMessages()
            && context != null;
    boolean baselineHasClientTools =
        incrementalSystem && !SystemContext.baselineTools(path.entries()).isEmpty();
    List<ProviderToolDefinition> effectiveTools =
        context == null ? providerTools(spec.toolBindings()) : context.tools();
    String systemInstruction = systemInstruction(path, spec, context, incrementalSystem);
    List<ProviderToolDefinition> tools =
        baselineHasClientTools ? SystemContext.baselineTools(path.entries()) : effectiveTools;
    List<ProviderMessage> messages =
        new ModelHistoryProjection(spec, incrementalSystem, baselineHasClientTools).project(path);
    return new ProviderRequest(
        spec.model(),
        requestVariant(path, spec, context, incrementalSystem, baselineHasClientTools),
        spec.outputTokens(),
        systemInstruction,
        messages,
        tools,
        cacheControl(path, spec, messages),
        baselineHasClientTools);
  }

  /** 增量 system 协议从 baseline 起始，后续变更作为消息进入；否则整段历史使用压缩后的 instruction。 */
  private static String systemInstruction(
      EntryPath path, ModelRequestSpec spec, SystemContext context, boolean incrementalSystem) {
    if (context == null) {
      return spec.systemInstruction();
    }
    return incrementalSystem
        ? SystemContext.baselineSystemInstruction(path.entries())
        : SystemContext.collapseSystemInstruction(path.entries());
  }

  /** 增量 system 协议把本次请求的 hosted tool 定义随 variant 冻结进协议选项。 */
  private static ModelVariant requestVariant(
      EntryPath path,
      ModelRequestSpec spec,
      SystemContext context,
      boolean incrementalSystem,
      boolean baselineHasClientTools) {
    ModelVariant variant = spec.variant();
    if (!incrementalSystem) {
      return variant;
    }
    List<SystemHostedTool> requestHostedTools =
        baselineHasClientTools
            ? SystemContext.baselineHostedTools(path.entries())
            : context.hostedTools();
    List<ProviderProtocolOptions.ToolDefinition> toolDefinitions =
        requestHostedTools.stream()
            .map(
                tool ->
                    new ProviderProtocolOptions.ToolDefinition(tool.name(), tool.definitionJson()))
            .toList();
    return new ModelVariant(
        variant.id(),
        variant.reasoningEffort(),
        variant.promptCacheRetention(),
        variant.protocolOptions().withToolDefinitions(toolDefinitions));
  }

  /**
   * 请求缓存控制：压缩分支第一次请求只读复用主 Thread 已写入的前缀，仅在切点前最后一条 assistant 之前的消息上放一个断点。
   *
   * <p>具体模型与产生该 assistant 的模型不同、或距该请求已超过留存 TTL 时退回常规会话缓存控制（不再额外写断点）。
   */
  private static ProviderCacheControl cacheControl(
      EntryPath path, ModelRequestSpec spec, List<ProviderMessage> messages) {
    String sessionId = path.root().sessionId().toString();
    PromptCacheRetention retention = spec.variant().promptCacheRetention();
    Integer breakpoint = branchMessageBreakpoint(path, spec, messages);
    return breakpoint == null
        ? ProviderCacheControl.session(retention, sessionId)
        : ProviderCacheControl.readOnlyMessage(retention, sessionId, breakpoint);
  }

  private static Integer branchMessageBreakpoint(
      EntryPath path, ModelRequestSpec spec, List<ProviderMessage> messages) {
    if (!cacheReuseEligible(path, spec)) {
      return null;
    }
    Optional<Entry> openTurn = openTurnStart(path);
    Optional<Entry> lastAssistant = lastAssistantEntry(path);
    if (openTurn.isEmpty()
        || lastAssistant.isEmpty()
        || !sameModelAsLastAssistant(lastAssistant.get(), spec)
        || cacheTtlExpired(lastAssistant.get(), openTurn.get(), spec)) {
      return null;
    }
    int lastAssistantMessage = lastAssistantMessageIndex(messages);
    return lastAssistantMessage <= 0 ? null : lastAssistantMessage - 1;
  }

  private static boolean cacheReuseEligible(EntryPath path, ModelRequestSpec spec) {
    return spec.providerType() == ProviderType.ANTHROPIC
        && spec.variant().promptCacheRetention() != PromptCacheRetention.NONE
        && openTurnStart(path).isPresent();
  }

  private static Optional<Entry> openTurnStart(EntryPath path) {
    return path.openTurnStart()
        .filter(
            entry ->
                entry.payload() instanceof TurnStartPayload start
                    && start.reason() == TurnStartReason.COMPACTION_BRANCH_INPUT);
  }

  private static Optional<Entry> lastAssistantEntry(EntryPath path) {
    Entry lastAssistant = null;
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        lastAssistant = entry;
      }
    }
    return Optional.ofNullable(lastAssistant);
  }

  private static boolean sameModelAsLastAssistant(Entry entry, ModelRequestSpec spec) {
    MessagePayload assistant = (MessagePayload) entry.payload();
    return assistant.assistantMetadata() != null
        && assistant.assistantMetadata().model() != null
        && spec.model().modelName().equals(assistant.assistantMetadata().model().modelName());
  }

  private static boolean cacheTtlExpired(
      Entry lastAssistant, Entry openTurn, ModelRequestSpec spec) {
    Duration ttl =
        spec.variant().promptCacheRetention() == PromptCacheRetention.LONG
            ? Duration.ofHours(1)
            : Duration.ofMinutes(5);
    return Duration.between(lastAssistant.createdAt(), openTurn.createdAt()).compareTo(ttl) > 0;
  }

  private static int lastAssistantMessageIndex(List<ProviderMessage> messages) {
    int lastAssistantMessage = -1;
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index).role() == ProviderMessageRole.ASSISTANT) {
        lastAssistantMessage = index;
      }
    }
    return lastAssistantMessage;
  }

  private List<ProviderToolDefinition> providerTools(List<ToolBinding> toolBindings) {
    List<ProviderToolDefinition> tools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      tools.add(providerTool(binding));
    }
    return List.copyOf(tools);
  }

  private ProviderToolDefinition providerTool(ToolBinding binding) {
    ToolDescriptor tool = binding.descriptor();
    return new ProviderToolDefinition(
        tool.name(), tool.description(), schemaCodec.encode(tool.inputSchema()));
  }
}
