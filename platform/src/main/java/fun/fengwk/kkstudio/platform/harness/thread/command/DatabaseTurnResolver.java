package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjectorContribution;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
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
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptPathResolver;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.contributor.ScopedBranchView;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleContextProjector;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleToolSelector;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 outputTokens。
 *
 * <p>输入事实只有 candidate path 的 {@link BranchSettings}（agentName / {@link ModelSelection} / 可空
 * environmentName）；实现按这些精确引用读取最新 {@link RuntimeToolCatalog} / environment 事实，Agent 的
 * tools/skills/subagents 每个新 turn 都从最新 Agent 配置派生，绝不回读 Chat defaults，也绝不静默丢弃缺失能力。Environment 只由
 * {@link BranchSettings#environmentName()} 在每轮 turn 开始时按全局唯一且不可变的 name 解析出当时 的内部路由身份（{@link
 * EnvironmentId}）：要求环境的工具（REQUIRED）在 Branch 未选择 Environment 时从最终模型工具列表过滤，已选择环境时按解析出的环境绑定； OPTIONAL
 * 工具在已选环境时绑定环境，未选环境时两者为 null；NONE 工具始终不绑定环境；name 无法解析时确定性拒绝规划；Skill 引用按 (packageName, name) 解析为
 * Prompt 三元组；内容由统一 read 按稳定 path 读取。环境上下文（name、system、user、home、date、note）只来自选中的 Environment
 * 与连接行保留的最近一次 READY 宿主 payload；引用缺失或失效时确定性拒绝规划 （返回 {@link Result.Rejected}，稳定 error code {@value
 * #REJECTION_CODE}）。只有 repository / registry 等基础设施异常向上传播， 由 ThreadProcessor reschedule。YOLO 不进入
 * spec。非工具元数据（context projectors）从保留的 {@link HarnessCatalog} 提取。
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
  private final EnvironmentRepository environmentRepository;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final SkillPromptPathResolver skillPromptPathResolver;
  private final CompactionConfigProvider compactionConfigProvider;
  private final AgentPromptComposer promptComposer;
  private final ProjectRoleToolSelector roleToolSelector;
  private final ProjectRoleContextProjector roleContextProjector;
  private final Clock clock;
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
      EnvironmentRepository environmentRepository,
      SkillCatalogQueryService skillCatalogQueryService,
      SkillPromptPathResolver skillPromptPathResolver,
      CompactionConfigProvider compactionConfigProvider,
      AgentPromptComposer promptComposer,
      ProjectRoleToolSelector roleToolSelector,
      ProjectRoleContextProjector roleContextProjector,
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
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.skillCatalogQueryService =
        Objects.requireNonNull(skillCatalogQueryService, "skillCatalogQueryService");
    this.skillPromptPathResolver =
        Objects.requireNonNull(skillPromptPathResolver, "skillPromptPathResolver");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.roleToolSelector = Objects.requireNonNull(roleToolSelector, "roleToolSelector");
    this.roleContextProjector =
        Objects.requireNonNull(roleContextProjector, "roleContextProjector");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.schemaCodec = new SchemaJsonCodec();
    this.cacheKeyFactory = new PromptCacheAffinityKeyFactory();
  }

  @Override
  public Result resolve(
      UUID threadId, EntryPath path, CompactionPreparation compactionPreparation) {
    Objects.requireNonNull(path, "path");
    try {
      if (compactionPreparation != null) {
        return resolveCompaction(path, compactionPreparation);
      }
      Objects.requireNonNull(threadId, "threadId");
      return resolved(plan(threadId, path));
    } catch (Rejection rejection) {
      // 只把显式构造的确定性拒绝转为 typed Rejected；repository/registry 等基础设施异常原样传播。
      return rejected(rejection.getMessage());
    }
  }

  /**
   * 只读现算 live 规划：与 {@link #resolve} 共用同一条 Agent/Environment/Tool/Skill 解析纯函数，供 Debug 预览直接消费。
   *
   * <p>确定性拒绝被投影为 {@link LiveTurnPlan.Rejected}（稳定 {@value #REJECTION_CODE}）；repository / registry /
   * projector 等基础设施或编程异常照常传播， 绝不伪装成 planning 失败。压缩路径不属于本入口。
   */
  public LiveTurnPlan planLive(UUID threadId, EntryPath path) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(path, "path");
    try {
      return plan(threadId, path);
    } catch (Rejection rejection) {
      return new LiveTurnPlan.Rejected(REJECTION_CODE, rejection.getMessage());
    }
  }

  private static TurnResolver.Resolved resolved(LiveTurnPlan.Planned plan) {
    return new TurnResolver.Resolved(plan.spec(), plan.contextWindow(), plan.spec().outputTokens());
  }

  private LiveTurnPlan.Planned plan(UUID threadId, EntryPath path) {
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
    Environment selectedEnvironment = resolveEnvironment(settings.environmentName());
    CurrentEnvironmentContext currentEnvironment = currentEnvironment(selectedEnvironment, now);
    // 路由身份与 prompt 上下文同源于选中的 Environment，绝不各自回退。
    EnvironmentId environmentId = currentEnvironment.environmentId();
    List<LiveTurnPlan.PlannedSkill> skills = resolveSkills(environmentId, agentConfig.getSkills());
    List<SubagentBinding> subagentBindings = resolveSubagents(agentConfig.getSubagents());
    List<String> toolNames = resolveToolNames(agentConfig, threadId);
    PlannedTools tools = resolveTools(environmentId, settings.environmentName(), toolNames);
    List<ToolBinding> toolBindings = tools.bindings();

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
    String systemInstruction =
        systemInstruction(
            threadId,
            agent.getSystemPrompt(),
            currentEnvironment,
            skills.stream().map(LiveTurnPlan.PlannedSkill::promptEntry).toList(),
            subagentBindings,
            path);
    int contextWindow = contextWindow(parsedModel);
    int outputTokens =
        outputTokens(
            parsedModel,
            contextWindow,
            CompactionPlanner.estimateRequestTokens(path, systemInstruction));
    ProviderCacheControl cacheControl =
        cacheControl(
            descriptor,
            variant,
            outputTokens,
            systemInstruction,
            toolBindings,
            sessionId,
            providerConnectionGenerationId,
            cachePolicy(providerFactory, provider.getConfigJson(), selection.providerName()));
    return new LiveTurnPlan.Planned(
        new ModelRequestSpec(
            providerType,
            providerConnectionGenerationId,
            descriptor,
            variant,
            outputTokens,
            systemInstruction,
            toolBindings,
            subagentBindings,
            cacheControl),
        contextWindow,
        tools.candidates(),
        skills);
  }

  /**
   * Environment 只由当前 branch settings 的可空 name 决定：null 表示未选择环境；非 null 时按全局唯一且不可变的 name 查 Environment
   * 并取内部路由身份，缺失时确定性拒绝规划。
   */
  private Environment resolveEnvironment(String environmentName) {
    if (environmentName == null) {
      return null;
    }
    Environment environment = environmentRepository.getByName(environmentName);
    if (environment == null) {
      throw rejection("environment not found: " + environmentName);
    }
    return environment;
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
            CompactionPrompts.summarizationSystemPrompt(),
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

  /** 从最新 Agent 配置派生本 turn 的模型可见工具名；随后注入 Project 角色工具。 */
  private List<String> resolveToolNames(AgentDefinitionConfigDTO config, UUID threadId) {
    LinkedHashSet<String> toolNames = new LinkedHashSet<>();
    for (String toolName : config.getTools()) {
      if (toolName == null || toolName.isBlank()) {
        throw rejection("invalid agent tool name: " + toolName);
      }
      ToolContribution contribution = toolCatalog.findTool(toolName).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + toolName);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw rejection("internal tool cannot be selected by an Agent: " + toolName);
      }
      if (!toolNames.add(toolName)) {
        throw rejection("duplicate agent tool name: " + toolName);
      }
    }
    if (!config.getSkills().isEmpty()) {
      toolNames.add(ReadTool.NAME);
    }
    if (!config.getSubagents().isEmpty()) {
      // task 只由非空 subagents allowlist 声明：递归深度是调用期 gate，绝不动态裁剪工具面。
      toolNames.add(TaskTool.NAME);
    }
    List<String> roleTools =
        Objects.requireNonNull(roleToolSelector.select(threadId), "role tools");
    toolNames.addAll(roleTools);
    return List.copyOf(toolNames);
  }

  /**
   * 按最新 Agent 配置派生的精确顺序逐一规划候选工具。声明环境需求的工具（REQUIRED）在 branch 未选择环境时从最终模型工具面过滤，
   * 绝不拒绝规划，但候选事实仍保留并携带唯一稳定原因；branch 已选择环境时冻结内部路由身份与环境名。OPTIONAL 工具在已选环境时冻结环境， 未选环境时两者为 null；NONE
   * 工具始终不绑定环境。贡献声明的固定 {@code requiredEnvironmentId} 只在 branch 已选择另一个非 null 环境时确定性拒绝。
   * 缺失能力仍立即拒绝，绝不静默跳过。
   *
   * <p>返回的候选顺序固定为「SENT 在前、FILTERED 在后」，与 {@link LiveTurnPlan.Planned} 的投影契约一致。
   */
  private PlannedTools resolveTools(
      EnvironmentId environmentId, String environmentName, List<String> toolNames) {
    List<ToolBinding> bindings = new ArrayList<>(toolNames.size());
    List<LiveTurnPlan.CandidateTool> sent = new ArrayList<>(toolNames.size());
    List<LiveTurnPlan.CandidateTool> filtered = new ArrayList<>();
    for (String toolName : toolNames) {
      ToolContribution contribution = toolCatalog.findTool(toolName).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + toolName);
      }
      EnvironmentSupport environmentSupport = contribution.requirements().environmentSupport();
      EnvironmentId toolRequiredEnv = contribution.requirements().requiredEnvironmentId();
      String inputSchemaJson =
          schemaCodec.encode(contribution.definition().descriptor().inputSchema());
      if (environmentId == null && environmentSupport == EnvironmentSupport.REQUIRED) {
        // 未选择环境时只从最终模型工具面过滤 REQUIRED 工具：候选仍可见，并携带唯一稳定原因。
        filtered.add(
            candidateTool(
                contribution,
                inputSchemaJson,
                LiveTurnPlan.ToolState.FILTERED,
                LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED));
        continue;
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
      if (toolRequiredEnv != null
          && environmentId != null
          && !toolRequiredEnv.equals(environmentId)) {
        throw rejection(
            "tool "
                + toolName
                + " requires environment "
                + toolRequiredEnv
                + " but branch has environment "
                + environmentId);
      }
      EnvironmentId boundEnvironmentId =
          environmentSupport == EnvironmentSupport.NONE ? null : environmentId;
      String boundEnvironmentName =
          environmentSupport == EnvironmentSupport.NONE ? null : environmentName;
      bindings.add(
          new ToolBinding(
              contribution.definition(),
              contributor,
              environmentSupport,
              boundEnvironmentId,
              boundEnvironmentName));
      sent.add(candidateTool(contribution, inputSchemaJson, LiveTurnPlan.ToolState.SENT, null));
    }
    List<LiveTurnPlan.CandidateTool> candidates = new ArrayList<>(sent.size() + filtered.size());
    candidates.addAll(sent);
    candidates.addAll(filtered);
    return new PlannedTools(List.copyOf(bindings), List.copyOf(candidates));
  }

  private static LiveTurnPlan.CandidateTool candidateTool(
      ToolContribution contribution,
      String inputSchemaJson,
      LiveTurnPlan.ToolState state,
      LiveTurnPlan.FilterReason filterReason) {
    return new LiveTurnPlan.CandidateTool(
        contribution.definition().descriptor().name(),
        contribution.definition().descriptor().description(),
        inputSchemaJson,
        contribution.requirements().environmentSupport(),
        contribution.requirements().requiredEnvironmentId(),
        contribution.id().toString(),
        state,
        filterReason);
  }

  /**
   * Agent skills 只从最新 Agent config 读取，并按全局 Skill 目录解析为冻结事实。
   *
   * <p>通过 {@link SkillCatalogQueryService#getPackage(String)} 读取 Platform 权威事实，按 (packageName,
   * name) 精确匹配；缺失或名称不存在时确定性拒绝。Skills 与 Environment 无关，未选择环境的 branch 同样可以规划。
   *
   * <p>路径与交付方式由 {@link SkillPromptPathResolver} 一次判定：只有选定 Environment 的 skill_state 记录的已安装 commit 与
   * Package 的 currentCommit 完全一致时才冻结 LOCAL 本地稳定路径，否则冻结 PLATFORM URI；同步失败时仍如实暴露实际 installedCommit。
   */
  private List<LiveTurnPlan.PlannedSkill> resolveSkills(
      EnvironmentId environmentId, List<SkillRefDTO> skillRefs) {
    if (skillRefs == null || skillRefs.isEmpty()) {
      return List.of();
    }
    List<LiveTurnPlan.PlannedSkill> skills = new ArrayList<>(skillRefs.size());
    for (SkillRefDTO ref : skillRefs) {
      if (ref == null || ref.getPackageName() == null || ref.getName() == null) {
        throw rejection("invalid agent skill reference");
      }
      SkillPackage pkg = skillCatalogQueryService.getPackage(ref.getPackageName());
      if (pkg == null) {
        throw rejection(
            "agent skill not found in the global catalog: "
                + ref.getPackageName()
                + "/"
                + ref.getName());
      }
      SkillManifestEntry entry = pkg.findSkill(ref.getName());
      if (entry == null) {
        throw rejection(
            "agent skill not found in the global catalog: "
                + ref.getPackageName()
                + "/"
                + ref.getName());
      }
      SkillPromptResolution resolution =
          skillPromptPathResolver.resolveDetails(
              environmentId, ref.getPackageName(), ref.getName(), pkg.getCurrentCommit());
      skills.add(
          new LiveTurnPlan.PlannedSkill(
              pkg.getPackageName(),
              entry.name(),
              entry.description(),
              resolution.path(),
              resolution.delivery(),
              pkg.getCurrentCommit(),
              pkg.getObservedHeadCommit(),
              resolution.installedCommit()));
    }
    return List.copyOf(skills);
  }

  /**
   * 当前 Environment 的宿主事实只来自最近一次被接受的 READY payload：连接行从未 READY 时所有可选事实整行省略，绝不伪造或回退到 Platform
   * 主机；日期用宿主时区换算，未报告时用 Platform 时钟时区。未选择 Environment 时只保留 Platform 当前日期。
   */
  private CurrentEnvironmentContext currentEnvironment(Environment selected, Instant now) {
    if (selected == null) {
      return CurrentEnvironmentContext.none(now, clock.getZone());
    }
    EnvironmentId environmentId = EnvironmentId.of(selected.getId());
    EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
    return CurrentEnvironmentContext.selected(
        environmentId, selected.getName(), hostFacts(connection), now, clock.getZone());
  }

  private static DaemonEnvironmentInfo hostFacts(EnvironmentConnection connection) {
    return connection == null || connection.daemonCapabilities() == null
        ? null
        : connection.daemonCapabilities().environment();
  }

  /**
   * task 只由最新 Agent 的非空 subagents allowlist 绑定：名称与描述在 ModelRequestSpec 中冻结，Tool 执行绝不依据后续 Agent
   * 配置扩权，也不按 Session depth 动态移除声明；递归上限由 {@code task} 调用期的深度 gate 拒绝。
   */
  private List<SubagentBinding> resolveSubagents(List<String> names) {
    if (names.isEmpty()) {
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

  /**
   * 本轮请求唯一的系统指令：Agent 正文 / Environment / Skills / Subagents 组合段，加上 Project 角色上下文与注册顺序稳定的
   * Contributor context projector 片段，用空行确定性拼接为一个字符串——不写入任何会话消息。
   */
  private String systemInstruction(
      UUID threadId,
      String systemPrompt,
      CurrentEnvironmentContext currentEnvironment,
      List<SkillPromptEntry> skills,
      List<SubagentBinding> subagentBindings,
      EntryPath path) {
    List<String> sections = new ArrayList<>();
    addSection(
        sections,
        promptComposer.compose(systemPrompt, currentEnvironment, skills, subagentBindings));
    Optional<String> roleContext =
        Objects.requireNonNull(roleContextProjector.project(threadId), "role context");
    roleContext.ifPresent(context -> addSection(sections, context));
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
        addSection(sections, fragment.text());
      }
    }
    if (sections.isEmpty()) {
      throw rejection("system instruction must not be blank");
    }
    return String.join("\n\n", sections);
  }

  private static void addSection(List<String> sections, String section) {
    if (section != null && !section.isBlank()) {
      sections.add(section);
    }
  }

  private ProviderCacheControl cacheControl(
      ModelDescriptor descriptor,
      ModelVariant variant,
      int outputTokens,
      String systemInstruction,
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
            systemInstruction,
            List.of(),
            providerTools,
            ProviderCacheControl.none());
    return new PromptCacheRequestFinalizer(
            sessionId, providerConnectionGenerationId, cacheKeyFactory)
        .apply(stub, cachePolicy)
        .cacheControl();
  }

  /** 一次工具规划的全部事实：冻结进 spec 的 SENT bindings 与「SENT 在前、FILTERED 在后」的完整候选投影。 */
  private record PlannedTools(
      List<ToolBinding> bindings, List<LiveTurnPlan.CandidateTool> candidates) {}

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
