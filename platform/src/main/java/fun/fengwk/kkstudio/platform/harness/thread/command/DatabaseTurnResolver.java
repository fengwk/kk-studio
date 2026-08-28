package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjectorContribution;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
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
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 maxOutputTokens。
 *
 * <p>输入事实只有 candidate path 的 {@link BranchSettings}（environment binding / agentName / {@link
 * ModelSelection}）；实现按这些精确引用读取最新 catalog / environment 事实，Agent 的 toolIds/skills/subagents 每个新 turn
 * 都从最新 Agent 配置派生，绝不回读 Chat defaults，也绝不静默丢弃缺失能力。environment 是完整 binding（路由名 + workspace path），为最新
 * branch 的不可变事实：ENVIRONMENT 工具一律按最新 {@code settings.environment()} 绑定（未选定环境时确定性拒绝规划）；Agent skills
 * 要求最新 Environment 提供 live descriptors，缺失/未 READY 时确定性拒绝 且绝不回看更旧的 branch settings。配置或 Environment
 * 不满足一律返回 {@link Result.Rejected}（稳定 error code {@value #REJECTION_CODE}）；只有 repository / registry
 * 等基础设施异常向上传播，由 ThreadProcessor reschedule。YOLO 不进入 spec。
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
  private final HarnessCatalog catalog;
  private final LiveEnvironmentRegistry environmentRegistry;
  private final SystemSettingsSnapshot snapshot;
  private final CompactionConfigProvider compactionConfigProvider;
  private final SubagentConfigProvider subagentConfigProvider;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;
  private final ProviderMessageProjector messageProjector;
  private final ToolDescriptorJsonCodec toolDescriptorCodec;
  private final PromptCacheAffinityKeyFactory cacheKeyFactory;

  public DatabaseTurnResolver(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      HarnessCatalog catalog,
      LiveEnvironmentRegistry environmentRegistry,
      SystemSettingsSnapshot snapshot,
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
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.subagentConfigProvider =
        Objects.requireNonNull(subagentConfigProvider, "subagentConfigProvider");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.messageProjector = new ProviderMessageProjector();
    this.toolDescriptorCodec = new ToolDescriptorJsonCodec();
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
    CurrentEnvironmentContext currentEnvironment =
        resolveCurrentEnvironment(settings.environment(), now);
    List<SkillBinding> skillBindings = resolveSkills(agentConfig.getSkills(), settings, now);
    List<SubagentBinding> subagentBindings = resolveSubagents(agentConfig.getSubagents(), path);
    List<AgentToolId> toolIds = resolveToolIds(agentConfig, path);
    List<ToolBinding> toolBindings = resolveTools(settings, toolIds);

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
            parsedModel.inputModalities(),
            parsedModel.tools(),
            parsedModel.reasoning(),
            parsedModel.pricing());
    List<AgentMessage> preamble =
        preambleMessages(
            agent.getSystemPrompt(), currentEnvironment, skillBindings, subagentBindings, path);
    ProviderCacheControl cacheControl =
        cacheControl(
            descriptor, variant, preamble, toolBindings, sessionId, cachePolicy(providerFactory));
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            providerType,
            descriptor,
            variant,
            preamble,
            toolBindings,
            skillBindings,
            subagentBindings,
            cacheControl),
        contextWindow(parsedModel),
        maxOutputTokens(parsedModel, variant));
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
    int actualModelMaxOutput = maxOutputTokens(parsedModel, variant);
    long budget =
        compactionConfigProvider
            .compactionConfig()
            .outputBudget(
                preparation.phase(), actualModelMaxOutput, preparation.removedPrefixTokens());
    if (budget <= 0 || budget > Integer.MAX_VALUE) {
      throw rejection("compaction output budget must be a positive int, got " + budget);
    }
    int maxOutput = (int) budget;
    ModelVariant compactionVariant =
        new ModelVariant(
            variant.id(),
            maxOutput,
            variant.temperature(),
            variant.topP(),
            variant.topK(),
            variant.frequencyPenalty(),
            variant.presencePenalty(),
            variant.stopSequences(),
            variant.reasoningEffort());
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            providerType,
            new ModelDescriptor(
                selection.providerName(),
                selection.modelName(),
                parsedModel.inputModalities(),
                parsedModel.tools(),
                parsedModel.reasoning(),
                parsedModel.pricing()),
            compactionVariant,
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

  /** variant 显式上限优先，否则使用 model 全局 limit.output；结果必须是正 int。 */
  private static int maxOutputTokens(ParsedAgentModelConfig parsedModel, ModelVariant variant) {
    long maxOutputTokens =
        variant.maxOutputTokens() == null
            ? parsedModel.maxOutputTokens()
            : variant.maxOutputTokens();
    if (maxOutputTokens <= 0 || maxOutputTokens > Integer.MAX_VALUE) {
      throw rejection("model limit.output must be a positive int, got " + maxOutputTokens);
    }
    return (int) maxOutputTokens;
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
      ToolContribution contribution = catalog.findTool(id).orElse(null);
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
   * 按最新 Agent 配置派生的精确顺序逐一绑定。ENVIRONMENT_CAPABILITY 工具一律绑定最新 branch 的完整 {@code
   * settings.environment()} binding（分支未选定环境时确定性拒绝规划）；所有 backend 冻结
   * ContributorBinding。缺失能力仍立即拒绝，绝不静默跳过。
   */
  private List<ToolBinding> resolveTools(BranchSettings settings, List<AgentToolId> toolIds) {
    List<ToolBinding> bindings = new ArrayList<>(toolIds.size());
    for (AgentToolId id : toolIds) {
      ToolContribution contribution = catalog.findTool(id).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + id);
      }
      List<ContributorStateAccess> stateAccesses;
      if (contribution instanceof DeclarativeToolContribution declarative) {
        stateAccesses =
            declarative.stateAccesses().stream()
                .map(
                    access ->
                        new ContributorStateAccess(
                            access.customType(),
                            ContributorStateAccessMode.valueOf(access.mode().name())))
                .toList();
      } else {
        stateAccesses = List.of();
      }
      ContributorBinding contributor =
          new ContributorBinding(
              contribution.id().contributorId().value(),
              contribution.id().localName(),
              stateAccesses);
      EnvironmentBinding environment =
          contribution.definition().backend() == AgentToolBackend.ENVIRONMENT_CAPABILITY
              ? settings.environment()
              : null;
      if (contribution.definition().backend() == AgentToolBackend.ENVIRONMENT_CAPABILITY
          && environment == null) {
        throw rejection(
            "environment tool "
                + id
                + " requires an environment binding but the branch has no environment");
      }
      bindings.add(new ToolBinding(contribution.definition(), contributor, environment));
    }
    return List.copyOf(bindings);
  }

  /** Agent skills 只从最新 Agent config 读取，且必须由最新选中的 Environment 精确提供。 */
  private List<SkillBinding> resolveSkills(
      List<String> skillNames, BranchSettings settings, Instant now) {
    if (skillNames.isEmpty()) {
      return List.of();
    }
    EnvironmentBinding binding = settings.environment();
    if (binding == null) {
      throw rejection(
          "agent skills require the latest selected environment but the branch has no environment");
    }
    EnvironmentName environmentName = binding.environmentName();
    // skills 需要最新 Environment 提供 live descriptors：按最新名称精确查找并要求 READY（同一可用性规则），
    // 缺失/未 READY 确定性拒绝，绝不回看更旧的 branch settings。
    LiveEnvironment environment = environmentRegistry.find(environmentName).orElse(null);
    if (environment == null) {
      throw rejection(
          "agent skills require the latest selected environment which is not live: "
              + environmentName);
    }
    if (!environment.isReady(
        now, Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis()))) {
      throw rejection(
          "agent skills require the latest selected environment which is not ready: "
              + environmentName);
    }
    List<SkillBinding> bindings = new ArrayList<>(skillNames.size());
    for (String skillName : skillNames) {
      DaemonSkillDescriptor skill =
          environment.skills().stream()
              .filter(candidate -> candidate.name().equals(skillName))
              .findFirst()
              .orElse(null);
      if (skill == null) {
        throw rejection("skill not found on the latest environment " + binding + ": " + skillName);
      }
      bindings.add(new SkillBinding(skill.name(), skill.description(), binding));
    }
    return List.copyOf(bindings);
  }

  private CurrentEnvironmentContext resolveCurrentEnvironment(
      EnvironmentBinding binding, Instant now) {
    if (binding == null) {
      return new CurrentEnvironmentContext(
          null, null, now.atZone(clock.getZone()).toLocalDate(), null);
    }
    LiveEnvironment liveEnvironment =
        environmentRegistry.find(binding.environmentName()).orElse(null);
    DaemonEnvironmentInfo environmentInfo =
        liveEnvironment == null || liveEnvironment.daemonCapabilities() == null
            ? null
            : liveEnvironment.daemonCapabilities().environment();
    ZoneId zone = environmentInfo == null ? clock.getZone() : ZoneId.of(environmentInfo.timeZone());
    return new CurrentEnvironmentContext(
        binding,
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

  private static PromptCachePolicy cachePolicy(ProviderFactory providerFactory) {
    PromptCacheCapability capability = providerFactory.promptCacheCapability();
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
    BranchView branch = new BranchView(path);
    for (ContextProjectorContribution contribution : catalog.contextProjectors()) {
      List<AgentMessage> projected =
          Objects.requireNonNull(
              contribution.projector().project(branch),
              "contributor context projector returned null: " + contribution.id());
      for (AgentMessage message : projected) {
        preamble.add(
            Objects.requireNonNull(
                message,
                "contributor context projector returned a null message: " + contribution.id()));
      }
    }
    return List.copyOf(preamble);
  }

  private ProviderCacheControl cacheControl(
      ModelDescriptor descriptor,
      ModelVariant variant,
      List<AgentMessage> preamble,
      List<ToolBinding> toolBindings,
      UUID sessionId,
      PromptCachePolicy cachePolicy) {
    List<ProviderToolDefinition> providerTools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      ToolDescriptor tool = binding.descriptor();
      providerTools.add(
          new ProviderToolDefinition(
              tool.name(),
              tool.description(),
              toolDescriptorCodec.encodeInputSchema(tool.inputSchema())));
    }
    ProviderRequest stub =
        new ProviderRequest(
            descriptor,
            variant,
            messageProjector.project(preamble),
            providerTools,
            ProviderCacheControl.none());
    return new PromptCacheRequestFinalizer(sessionId, cacheKeyFactory)
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
