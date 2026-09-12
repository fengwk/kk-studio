package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjectorContribution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.harness.contributor.ScopedBranchView;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 outputTokens。
 *
 * <p>输入事实只有 candidate path 的 {@link BranchSettings}（agentName / {@link ModelSelection}）；实现按这些精确引用读取
 * 最新 {@link RuntimeToolCatalog} / environment 事实，Agent 的 toolIds/skills/subagents 每个新 turn 都从最新
 * Agent 配置派生，绝不回读 Chat defaults，也绝不静默丢弃缺失能力。Environment 由 AgentDefinition.environmentId 在每轮 turn
 * 开始时 按引用解析出当时事实（{@link EnvironmentId}）：要求环境的工具一律按最新解析出的环境绑定（未选定环境时确定性拒绝规划）； Agent skills 要求最新
 * Environment 提供 live descriptors，缺失/未 READY 时确定性拒绝。配置或 Environment 不满足一律返回 {@link
 * Result.Rejected}（稳定 error code {@value #REJECTION_CODE}）；只有 repository / registry 等基础设施异常向上传播， 由
 * ThreadProcessor reschedule。YOLO 不进入 spec。非工具元数据（context projectors）从保留的 {@link HarnessCatalog}
 * 提取。
 */
@Component
public final class DatabaseTurnResolver implements TurnResolver {

  /** 所有确定性拒绝共用的稳定 AssistantError code。 */
  public static final String REJECTION_CODE = "PLANNING_FAILED";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final ProviderFactories providerFactories;
  private final RuntimeToolCatalog toolCatalog;
  private final HarnessCatalog harnessCatalog;
  private final EnvironmentRegistry environmentRegistry;
  private final CompactionConfigProvider compactionConfigProvider;
  private final SubagentConfigProvider subagentConfigProvider;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;
  private final ProviderMessageProjector messageProjector;
  private final SchemaJsonCodec schemaCodec;
  private final PromptCacheAffinityKeyFactory cacheKeyFactory;

  public DatabaseTurnResolver(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      RuntimeToolCatalog toolCatalog,
      HarnessCatalog harnessCatalog,
      EnvironmentRegistry environmentRegistry,
      CompactionConfigProvider compactionConfigProvider,
      SubagentConfigProvider subagentConfigProvider,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.harnessCatalog = Objects.requireNonNull(harnessCatalog, "harnessCatalog");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.subagentConfigProvider =
        Objects.requireNonNull(subagentConfigProvider, "subagentConfigProvider");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.messageProjector = new ProviderMessageProjector();
    this.schemaCodec = new SchemaJsonCodec();
    this.cacheKeyFactory = new PromptCacheAffinityKeyFactory();
  }

  @Override
  public Result resolve(
      UUID threadId, EntryPath path, CompactionPreparation compactionPreparation) {
    Objects.requireNonNull(path, "path");
    try {
      return compactionPreparation == null
          ? resolveLive(path)
          : resolveCompaction(path, compactionPreparation);
    } catch (Rejection rejection) {
      // 只把显式构造的确定性拒绝转为 typed Rejected；repository/registry 等基础设施异常原样传播。
      return rejected(rejection.getMessage());
    }
  }

  private Result resolveLive(EntryPath path) {
    BranchSettings settings = path.baseSettings();
    UUID sessionId = path.root().sessionId();

    AgentDefinition agent =
        require(
            agentDefinitionRepository.getByName(settings.agentName()),
            "agent not found: " + settings.agentName());
    AgentDefinitionConfigDTO agentConfig = decodeAgentConfig(agent);

    ModelSelection selection = settings.model();
    AgentProvider provider =
        require(
            providerRepository.getByName(selection.providerName()),
            "provider not found: " + selection.providerName());
    ProviderType providerType = provider.getProviderType();
    if (providerType == null) {
      throw rejection("provider type must not be null");
    }
    UUID providerConnectionGenerationId =
        require(
            provider.getConnectionGenerationId(),
            "provider connection generation id must not be null");
    AgentModel model =
        require(
            modelRepository.getByProviderNameAndName(
                selection.providerName(), selection.modelName()),
            "model not found: " + selection.providerName() + "/" + selection.modelName());
    ParsedAgentModelConfig parsedModel = parseModel(model);
    ModelVariant variant = findVariant(parsedModel, selection.variant());
    if (variant == null) {
      throw rejection(
          "model variant not found: "
              + selection.providerName()
              + "/"
              + selection.modelName()
              + " variant="
              + selection.variant());
    }
    ProviderFactory providerFactory =
        require(
            providerFactories.lookup(providerType).orElse(null),
            "provider factory not found for "
                + selection.providerName()
                + " ("
                + providerType
                + ")");

    Instant now = clock.instant();
    EnvironmentId environmentId = resolveEnvironmentId(agent);
    CurrentEnvironmentContext currentEnvironment = resolveCurrentEnvironment(environmentId, now);
    List<SkillBinding> skillBindings = resolveSkills(agentConfig.getSkills(), environmentId);
    List<SubagentBinding> subagentBindings = resolveSubagents(agentConfig.getSubagents(), path);
    List<AgentToolId> toolIds = resolveToolIds(agentConfig, path);
    List<ToolBinding> toolBindings = resolveTools(environmentId, toolIds);

    if (!toolBindings.isEmpty() && !parsedModel.tools()) {
      throw rejection(
          "model does not support tools: "
              + selection.providerName()
              + "/"
              + selection.modelName());
    }
    if (variant.reasoningEffort() != null && !parsedModel.reasoning()) {
      throw rejection(
          "model does not support reasoning: "
              + selection.providerName()
              + "/"
              + selection.modelName());
    }

    ModelDescriptor descriptor =
        new ModelDescriptor(
            selection.providerName(),
            selection.modelName(),
            model.getModelId(),
            parsedModel.inputModalities(),
            parsedModel.tools(),
            parsedModel.reasoning(),
            parsedModel.pricing());
    List<AgentMessage> preamble =
        preambleMessages(
            agent.getSystemPrompt(), currentEnvironment, skillBindings, subagentBindings, path);
    int outputTokens =
        outputTokens(
            parsedModel,
            contextWindow(parsedModel),
            CompactionPlanner.estimateRequestTokens(path, preamble));
    ProviderCacheControl cacheControl =
        cacheControl(
            descriptor,
            variant,
            outputTokens,
            preamble,
            toolBindings,
            sessionId,
            providerConnectionGenerationId,
            cachePolicy(providerFactory, provider.getConfigJson(), selection.providerName()));
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            providerType,
            providerConnectionGenerationId,
            descriptor,
            variant,
            outputTokens,
            preamble,
            toolBindings,
            skillBindings,
            subagentBindings,
            cacheControl),
        contextWindow(parsedModel),
        outputTokens);
  }

  /** 环境完全由 Agent definition 决定；目录不再参与工具绑定。 */
  private static EnvironmentId resolveEnvironmentId(AgentDefinition agent) {
    return agent.getEnvironmentId() == null ? null : EnvironmentId.of(agent.getEnvironmentId());
  }

  /**
   * 压缩 resolver 路径：只按 preparation 的 executionModel 查找 provider/model/variant 构造请求——不查 Agent system
   * prompt、不查 contributors、零 tool/skill、不做 environment 可用性查找、不做 prompt-cache finalizer / cache
   * 写入。切分事实由 candidate path 的 compaction TURN_START 持有，不复制进 spec。
   */
  private Result resolveCompaction(EntryPath path, CompactionPreparation preparation) {
    ModelSelection selection = preparation.executionModel();
    AgentProvider provider =
        require(
            providerRepository.getByName(selection.providerName()),
            "provider not found: " + selection.providerName());
    ProviderType providerType = provider.getProviderType();
    if (providerType == null) {
      throw rejection("provider type must not be null");
    }
    UUID providerConnectionGenerationId =
        require(
            provider.getConnectionGenerationId(),
            "provider connection generation id must not be null");
    AgentModel model =
        require(
            modelRepository.getByProviderNameAndName(
                selection.providerName(), selection.modelName()),
            "model not found: " + selection.providerName() + "/" + selection.modelName());
    ParsedAgentModelConfig parsedModel = parseModel(model);
    ModelVariant variant = findVariant(parsedModel, selection.variant());
    if (variant == null) {
      throw rejection(
          "model variant not found: "
              + selection.providerName()
              + "/"
              + selection.modelName()
              + " variant="
              + selection.variant());
    }
    if (variant.reasoningEffort() != null && !parsedModel.reasoning()) {
      throw rejection(
          "model does not support reasoning: "
              + selection.providerName()
              + "/"
              + selection.modelName());
    }
    long budget =
        compactionConfigProvider
            .compactionConfig()
            .outputBudget(
                preparation.phase(), outputTokens(parsedModel), preparation.removedPrefixTokens());
    if (budget <= 0 || budget > Integer.MAX_VALUE) {
      throw rejection("compaction output budget must be a positive int, got " + budget);
    }
    int maxOutput = (int) budget;
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            providerType,
            providerConnectionGenerationId,
            new ModelDescriptor(
                selection.providerName(),
                selection.modelName(),
                model.getModelId(),
                parsedModel.inputModalities(),
                parsedModel.tools(),
                parsedModel.reasoning(),
                parsedModel.pricing()),
            variant,
            maxOutput,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ProviderCacheControl.none()),
        contextWindow(parsedModel),
        maxOutput);
  }

  /** model config limit.context 必须是可表示的正 int；否则确定性拒绝。 */
  private static int contextWindow(ParsedAgentModelConfig parsedModel) {
    long contextWindow = parsedModel.contextWindow();
    if (contextWindow <= 0 || contextWindow > Integer.MAX_VALUE) {
      throw rejection("model limit.context must be a positive int, got " + contextWindow);
    }
    return (int) contextWindow;
  }

  /**
   * 普通请求输出预算：严格取 model 级 {@code limit.output} 与剩余上下文（{@code contextWindow -
   * 估算输入}）的较小者。剩余上下文无法提供正预算时 明确拒绝，绝不伪造一个可执行预算，也不回落到 variant 或请求体的自定义字段。
   */
  private static int outputTokens(
      ParsedAgentModelConfig parsedModel, int contextWindow, long estimatedInputTokens) {
    long limit = parsedModel.maxOutputTokens();
    if (limit <= 0 || limit > Integer.MAX_VALUE) {
      throw rejection("model limit.output must be a positive int, got " + limit);
    }
    long remaining = contextWindow - estimatedInputTokens;
    long outputTokens = Math.min(limit, remaining);
    if (outputTokens <= 0) {
      throw rejection(
          "remaining context leaves no positive output budget: contextWindow="
              + contextWindow
              + ", estimatedInputTokens="
              + estimatedInputTokens);
    }
    return (int) outputTokens;
  }

  /** 压缩阶段预算上限：model 级 {@code limit.output}，必须是可表示的正 int。 */
  private static int outputTokens(ParsedAgentModelConfig parsedModel) {
    long outputTokens = parsedModel.maxOutputTokens();
    if (outputTokens <= 0 || outputTokens > Integer.MAX_VALUE) {
      throw rejection("model limit.output must be a positive int, got " + outputTokens);
    }
    return (int) outputTokens;
  }

  private AgentDefinitionConfigDTO decodeAgentConfig(AgentDefinition agent) {
    try {
      return agentConfigCodec.decode(agent.getConfigJson());
    } catch (IllegalStateException error) {
      throw rejection("invalid agent configuration: " + messageOrClass(error));
    }
  }

  private ParsedAgentModelConfig parseModel(AgentModel model) {
    try {
      return modelConfigParser.parse(model.getConfigJson());
    } catch (IllegalArgumentException error) {
      throw rejection("invalid model configuration: " + messageOrClass(error));
    }
  }

  /** 从最新 Agent 配置派生本 turn 的稳定工具身份；内部工具只在对应能力当前启用时追加。 */
  private List<AgentToolId> resolveToolIds(AgentDefinitionConfigDTO config, EntryPath path) {
    List<AgentToolId> toolIds = new ArrayList<>(config.getToolIds().size() + 2);
    for (String value : config.getToolIds()) {
      AgentToolId id;
      try {
        id = new AgentToolId(value);
      } catch (RuntimeException error) {
        throw rejection("invalid agent tool id: " + value);
      }
      ToolContribution contribution = toolCatalog.findTool(id).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + id);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw rejection("internal tool cannot be selected by an Agent: " + id);
      }
      toolIds.add(id);
    }
    if (!config.getSkills().isEmpty()) {
      toolIds.add(BuiltinToolIds.LOAD_SKILL);
    }
    if (!config.getSubagents().isEmpty()
        && sessionDepth(path) < subagentConfigProvider.subagentConfig().maxDepth()) {
      toolIds.add(BuiltinToolIds.TASK);
    }
    return List.copyOf(toolIds);
  }

  /**
   * 按最新 Agent 配置派生的精确顺序逐一绑定。声明环境需求的工具一律绑定 Agent 选择的 {@code environmentId} （Agent
   * 未选择环境时确定性拒绝规划）；所有工具冻结 ContributorBinding 与 state accesses。缺失能力仍立即拒绝，绝不静默跳过。
   */
  private List<ToolBinding> resolveTools(EnvironmentId environmentId, List<AgentToolId> toolIds) {
    List<ToolBinding> bindings = new ArrayList<>(toolIds.size());
    for (AgentToolId id : toolIds) {
      ToolContribution contribution = toolCatalog.findTool(id).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + id);
      }
      List<ContributorStateAccess> stateAccesses =
          contribution.requirements().stateAccesses().stream()
              .map(
                  access ->
                      new ContributorStateAccess(
                          access.customType(),
                          ContributorStateAccessMode.valueOf(access.mode().name())))
              .toList();
      ContributorBinding contributor =
          new ContributorBinding(
              contribution.id().contributorId().value(),
              contribution.id().localName(),
              stateAccesses);
      boolean environmentRequired = contribution.requirements().environmentRequired();
      EnvironmentId requiredEnvironmentId = environmentRequired ? environmentId : null;
      if (environmentRequired && requiredEnvironmentId == null) {
        throw rejection(
            "environment tool "
                + id
                + " requires an environment binding but the agent has no environment");
      }
      bindings.add(
          new ToolBinding(
              contribution.definition(), contributor, environmentRequired, requiredEnvironmentId));
    }
    return List.copyOf(bindings);
  }

  /**
   * Agent skills 只从最新 Agent config 读取，且必须由 Agent 选择的 Environment 精确提供。
   *
   * <p>B2 之前的临时边界：选择仍基于当前 READY 快照的展平列表（READY 已保证 sourceId 唯一与名称全局唯一），冻结时写入全部 descriptor
   * 字段（sourceEnvironmentId/sourceId/name/description/baseDirectory/revision）。B2 改为读取权威持久 inventory。
   */
  private List<SkillBinding> resolveSkills(List<String> skillNames, EnvironmentId environmentId) {
    if (skillNames.isEmpty()) {
      return List.of();
    }
    if (environmentId == null) {
      throw rejection("agent skills require an environment but the agent has no environment");
    }
    // skills 需要 Agent Environment 提供 live descriptors：按冻结 id 精确查找并要求 READY（同一可用性规则）。
    EnvironmentConnection environment = environmentRegistry.find(environmentId).orElse(null);
    if (environment == null) {
      throw rejection(
          "agent skills require the selected environment which is not live: " + environmentId);
    }
    if (!environmentRegistry.hasReadyLease(environmentId)) {
      throw rejection(
          "agent skills require the selected environment which is not ready: " + environmentId);
    }
    List<SkillBinding> bindings = new ArrayList<>(skillNames.size());
    for (String skillName : skillNames) {
      DaemonSkillDescriptor skill =
          environment.skills().stream()
              .filter(candidate -> candidate.name().equals(skillName))
              .findFirst()
              .orElse(null);
      if (skill == null) {
        throw rejection(
            "skill not found on the latest environment " + environmentId + ": " + skillName);
      }
      bindings.add(
          new SkillBinding(
              environmentId,
              skill.sourceId(),
              skill.name(),
              skill.description(),
              skill.baseDirectory(),
              skill.contentRevision()));
    }
    return List.copyOf(bindings);
  }

  private CurrentEnvironmentContext resolveCurrentEnvironment(
      EnvironmentId environmentId, Instant now) {
    if (environmentId == null) {
      return new CurrentEnvironmentContext(
          null, null, now.atZone(clock.getZone()).toLocalDate(), null);
    }
    EnvironmentConnection liveEnvironment = environmentRegistry.find(environmentId).orElse(null);
    DaemonEnvironmentInfo environmentInfo =
        liveEnvironment == null || liveEnvironment.daemonCapabilities() == null
            ? null
            : liveEnvironment.daemonCapabilities().environment();
    ZoneId zone = environmentInfo == null ? clock.getZone() : ZoneId.of(environmentInfo.timeZone());
    return new CurrentEnvironmentContext(
        environmentId,
        environmentInfo == null ? null : environmentInfo.operatingSystem(),
        now.atZone(zone).toLocalDate(),
        environmentInfo == null ? null : environmentInfo.note());
  }

  /**
   * task 由最新 Agent allowlist 且当前 Session depth 小于部署上限时绑定。名称与描述在 ModelRequestSpec 中冻结， Tool 执行绝不依据后续
   * Agent 配置扩权。
   */
  private List<SubagentBinding> resolveSubagents(List<String> names, EntryPath path) {
    if (names.isEmpty()
        || sessionDepth(path) >= subagentConfigProvider.subagentConfig().maxDepth()) {
      return List.of();
    }
    List<SubagentBinding> bindings = new ArrayList<>(names.size());
    for (String name : names) {
      AgentDefinition subagent =
          require(agentDefinitionRepository.getByName(name), "subagent not found: " + name);
      bindings.add(
          new SubagentBinding(
              subagent.getName(),
              subagent.getDescription() == null ? "" : subagent.getDescription()));
    }
    return List.copyOf(bindings);
  }

  private static int sessionDepth(EntryPath path) {
    RootPayload root = (RootPayload) path.root().payload();
    return root.subagentContext() == null ? 1 : root.subagentContext().depth();
  }

  private static ModelVariant findVariant(ParsedAgentModelConfig model, String name) {
    return model.variants().stream()
        .filter(variant -> variant.id().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static PromptCachePolicy cachePolicy(
      ProviderFactory providerFactory, String configJson, String providerName) {
    PromptCacheCapability capability;
    try {
      capability = providerFactory.promptCacheCapability(configJson);
      if (capability == null) {
        throw new IllegalStateException("promptCacheCapability returned null");
      }
    } catch (Exception error) {
      throw rejection("invalid prompt cache configuration for provider: " + providerName);
    }
    PromptCacheRetention retention =
        capability.supports(PromptCacheRetention.SHORT)
            ? PromptCacheRetention.SHORT
            : PromptCacheRetention.NONE;
    return PromptCachePolicy.of(capability, retention);
  }

  private List<AgentMessage> preambleMessages(
      String systemPrompt,
      CurrentEnvironmentContext currentEnvironment,
      List<SkillBinding> skillBindings,
      List<SubagentBinding> subagentBindings,
      EntryPath path) {
    List<AgentMessage> preamble = new ArrayList<>();
    String composedPrompt =
        promptComposer.compose(systemPrompt, currentEnvironment, skillBindings, subagentBindings);
    if (!composedPrompt.isBlank()) {
      preamble.add(AgentMessage.system(composedPrompt));
    }
    for (ContextProjectorContribution contribution : harnessCatalog.contextProjectors()) {
      String contributorId = contribution.id().contributorId().value();
      BranchView branch = new ScopedBranchView(path.entries(), contributorId);
      List<ContextFragment> projected =
          Objects.requireNonNull(
              contribution.projector().project(branch),
              "contributor context projector returned null: " + contribution.id());
      for (ContextFragment fragment : projected) {
        Objects.requireNonNull(
            fragment,
            "contributor context projector returned a null fragment: " + contribution.id());
        preamble.add(AgentMessage.system(fragment.text()));
      }
    }
    return List.copyOf(preamble);
  }

  private ProviderCacheControl cacheControl(
      ModelDescriptor descriptor,
      ModelVariant variant,
      int outputTokens,
      List<AgentMessage> preamble,
      List<ToolBinding> toolBindings,
      UUID sessionId,
      UUID providerConnectionGenerationId,
      PromptCachePolicy cachePolicy) {
    List<ProviderToolDefinition> providerTools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      ToolDescriptor tool = binding.descriptor();
      providerTools.add(
          new ProviderToolDefinition(
              tool.name(), tool.description(), schemaCodec.encode(tool.inputSchema())));
    }
    ProviderRequest stub =
        new ProviderRequest(
            descriptor,
            variant,
            outputTokens,
            messageProjector.project(preamble),
            providerTools,
            ProviderCacheControl.none());
    return new PromptCacheRequestFinalizer(
            sessionId, providerConnectionGenerationId, cacheKeyFactory)
        .apply(stub, cachePolicy)
        .cacheControl();
  }

  private static <T> T require(T value, String message) {
    if (value == null) {
      throw rejection(message);
    }
    return value;
  }

  /** 确定性拒绝：只由显式调用产生，绝不捕获外部基础设施异常。 */
  private static Rejection rejection(String message) {
    return new Rejection(message);
  }

  private static Result rejected(String message) {
    return new TurnResolver.Rejected(new AssistantError(REJECTION_CODE, message));
  }

  private static String messageOrClass(RuntimeException error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 内部确定性拒绝信号：只在显式拒绝点抛出，由 {@link #resolve} 转换为 {@link Result.Rejected}。 */
  private static final class Rejection extends RuntimeException {
    private Rejection(String message) {
      super(message);
    }
  }
}
