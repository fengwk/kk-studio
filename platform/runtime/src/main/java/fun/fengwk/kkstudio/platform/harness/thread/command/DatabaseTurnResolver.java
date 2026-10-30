package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 outputTokens。
 *
 * <p>本解析器<strong>不反查 Thread 归属</strong>：Issue Run 的 Agent、Model、Environment、业务工具与身份上下文都在接受 Run
 * 时被显式提交或冻结到 branch state，普通 Thread 与 Issue Agent Thread 走同一条纯 branch 事实解析路径。Issue 语义由 {@code
 * project/run} contributor state 的 context projector 与运行期业务工具在自己的事务边界内校验，运行时不再判断"这个 Thread 属于哪个
 * Issue"，也不据此覆写 Environment 或注入工具。
 *
 * <p>普通 branch 的输入事实只有 candidate path 的 {@link BranchSettings}（agentName / {@link ModelSelection} /
 * 可空 environmentName）。Agent 目录、Environment、tools/skills/subagents 与 system instruction 由 {@link
 * DatabaseAgentTurnContextResolver} 在一次 catalog 租用内解析为与模型无关的 {@link AgentTurnContext}；本类只在这份
 * 快照上解析模型组、variant、压缩、缓存与输出预算。目录快照的确定性拒绝在本边界投影为 {@link Result.Rejected}（稳定 error code {@value
 * #REJECTION_CODE}），SQL 访问异常统一以 {@link TurnResolveTransientException} 上抛，由 ThreadProcessor 回滚事务后
 * reschedule；其它编程 / 契约异常仍向上传播并按确定性失败处理。YOLO 不进入 spec。
 */
@Component
public final class DatabaseTurnResolver implements TurnResolver {

  /** 所有确定性拒绝共用的稳定 AssistantError code。 */
  public static final String REJECTION_CODE = "PLANNING_FAILED";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final AgentModelGroupConfigCodec groupConfigCodec;
  private final ProviderFactories providerFactories;
  private final CompactionConfigProvider compactionConfigProvider;
  private final DatabaseAgentTurnContextResolver contextResolver;
  private final Clock clock;

  public DatabaseTurnResolver(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      AgentModelGroupConfigCodec groupConfigCodec,
      ProviderFactories providerFactories,
      CompactionConfigProvider compactionConfigProvider,
      DatabaseAgentTurnContextResolver contextResolver,
      Clock clock) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.groupConfigCodec = Objects.requireNonNull(groupConfigCodec, "groupConfigCodec");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
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
      return resolved(plan(path, path.baseSettings(), clock.instant()));
    } catch (Rejection | AgentTurnContextException rejection) {
      // 只把显式构造的确定性拒绝转为 typed Rejected；repository/registry 等基础设施异常按类型分别处理。
      return rejected(rejection.getMessage());
    } catch (DataAccessException failure) {
      // 解析在调用方 turn 事务内执行：任何 SQL 访问异常都可能已把该事务标记为 rollback-only，绝不能继续在同一事务里写
      // Rejected/FAILED。统一适配为显式 typed transient，由 Processor 整体回滚后在事务边界外受控 reschedule。
      throw new TurnResolveTransientException(
          "turn resolution infrastructure unavailable", failure);
    }
  }

  /**
   * 只读现算 live 规划：与 {@link #resolve} 共用同一条 Agent/Environment/Tool/Skill 解析纯函数，供 Debug 预览直接消费。
   *
   * <p>确定性拒绝被投影为 {@link LiveTurnPlan.Rejected}（稳定 {@value #REJECTION_CODE}）；repository / registry /
   * projector 等基础设施或编程异常照常传播， 绝不伪装成 planning 失败。压缩路径不属于本入口。
   */
  public LiveTurnPlan planLive(UUID threadId, EntryPath path) {
    return planLive(threadId, path, path.baseSettings(), clock.instant());
  }

  /**
   * 指定历史时刻的只读 live 规划：与 {@link #planLive(UUID, EntryPath)} 共用同一 planner，仅把 Environment
   * 宿主事实的「当前时间」替换为显式 {@code now}，供历史请求预览在所选 Entry 的真实时间点上重建。
   */
  public LiveTurnPlan planLive(UUID threadId, EntryPath path, Instant now) {
    return planLive(threadId, path, path.baseSettings(), now);
  }

  /**
   * 显式 settings 的只读 live 规划：与正式入口共用同一个 planner，只把 draft 的 Agent/Model/Environment/Goal 快照换成调用方 给出的
   * {@link BranchSettings}；EntryPath 本身不承载、也不被写入任何 draft。
   *
   * <p>供 Debug 预览以 UI 当前草稿选择现算，绝不改变 Thread 的持久事实：不落 Entry、不动 cursor、不做 CAS、不发布 Package、也不触发 Daemon
   * sync。
   */
  public LiveTurnPlan planLiveWithSettings(UUID threadId, EntryPath path, BranchSettings settings) {
    return planLive(threadId, path, settings, clock.instant());
  }

  private LiveTurnPlan planLive(
      UUID threadId, EntryPath path, BranchSettings settings, Instant now) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(now, "now");
    try {
      return plan(path, settings, now);
    } catch (Rejection | AgentTurnContextException rejection) {
      return new LiveTurnPlan.Rejected(REJECTION_CODE, rejection.getMessage());
    }
  }

  /**
   * 历史模型输出的只读规划：请求前缀是 ROOT 到该输出的 parent。
   *
   * <p>普通输出与 live 共用同一 planner（环境宿主事实用显式 {@code now}）。父 COMPACTION turn 自身不创建 ModelInvocation：它只冻结
   * run group 与输出预算，真实 provider 请求属于独立的压缩子 Thread（子请求按父冻结预算收紧），因此这里绝不伪造一份零工具请求，而是返回稳定 typed 拒绝。
   */
  public LiveTurnPlan planHistorical(UUID threadId, EntryPath path, Instant now) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(now, "now");
    if (isCompactionParentTurnStart(path.head())) {
      return new LiveTurnPlan.Rejected(
          REJECTION_CODE,
          "a COMPACTION parent turn has no model invocation; its provider request belongs to the"
              + " compaction child thread");
    }
    return planLive(threadId, path, now);
  }

  /** 请求头是否是父 COMPACTION turn 的 TURN_START：该回合只冻结 run group 与预算，从不发出 provider 请求。 */
  private static boolean isCompactionParentTurnStart(Entry head) {
    return head.payload() instanceof TurnStartPayload start
        && start.reason() == TurnStartReason.COMPACTION;
  }

  private static TurnResolver.Resolved resolved(LiveTurnPlan.Planned plan) {
    return new TurnResolver.Resolved(plan.spec(), plan.contextWindow(), plan.spec().outputTokens());
  }

  private LiveTurnPlan.Planned plan(EntryPath path, BranchSettings settings, Instant now) {
    UUID sessionId = path.root().sessionId();

    // 一次上下文解析租用一份 catalog 快照：Agent/Environment/tools/skills/subagents/prompt 同源。
    AgentTurnContext context = contextResolver.resolve(path, settings, now);
    ResolvedSelection resolved = resolveSelection(settings.model(), context.agent());
    MemberRuntime first = resolved.members().getFirst();

    for (MemberRuntime member : resolved.members()) {
      if (!context.toolBindings().isEmpty() && !member.parsed().tools()) {
        throw rejection("model does not support tools: " + member.modelName());
      }
      requireReasoningSupport(member);
    }

    String systemInstruction = context.systemInstruction();
    int contextWindow = resolved.contextWindow();
    long estimatedInputTokens = CompactionPlanner.estimateRequestTokens(path, systemInstruction);
    // 组内每个成员都必须容纳这次请求：输出预算取所有成员可提供预算的最小值。
    int outputTokens =
        resolved.members().stream()
            .mapToInt(
                member ->
                    outputTokens(
                        member.parsed(), contextWindow(member.parsed()), estimatedInputTokens))
            .min()
            .orElseThrow();
    ProviderCacheControl cacheControl =
        usesCompactionAgentCacheHint(settings)
            ? ProviderCacheControl.none()
            : ProviderCacheControl.session(
                promptCacheRetention(
                    first.factory(), first.provider().configJson(), first.provider().name()),
                sessionId.toString());
    return new LiveTurnPlan.Planned(
        new ModelRequestSpec(
            first.providerType(),
            first.providerConnectionGenerationId(),
            resolved.descriptor(),
            first.variant(),
            outputTokens,
            systemInstruction,
            context.toolBindings(),
            context.subagentBindings(),
            cacheControl,
            resolved.group()),
        contextWindow,
        context.candidateTools(),
        context.skills());
  }

  /**
   * 分支是否直接使用了内置压缩 Agent 名称：只影响 cache hint（这类分支不落会话级 cache key 或 Provider 自动缓存档位），绝不作为压缩身份权威。
   *
   * <p>压缩执行身份由 runtime 的 durable COMPACTION Join 事实判定：直接压缩子的每一次请求（含工具 loop turn 与失败重试）都由 runtime
   * 按父冻结 outputBudget 收紧并强制 NONE cache。普通分支即便直接选择该 Agent 名称，也只得到同样的 cache hint，不会改变、也不会覆盖该 Join 规则。
   */
  private static boolean usesCompactionAgentCacheHint(BranchSettings settings) {
    return BuiltinAgentDefinitions.COMPACTION_NAME.equals(settings.agentName());
  }

  /**
   * 解析本 turn 实际调用的逻辑模型（MODEL 或 GROUP）：Thread 设置的模型存在时使用它；已从 catalog 移除时改用当前 Agent 的默认模型；二者都不可用时拒绝本
   * turn。
   *
   * <p>Thread 设置不被改写：默认模型恢复或重新绑定后，下一 turn 立即生效。单 Model 的 variant 优先沿用原设置；目标模型没有该 variant
   * 时，原模型改用自身的默认 variant，回退模型依次取 Agent 声明的 variant 与模型自身的默认 variant。只对模型回退，Agent 与 Environment
   * 失效一律停止。
   */
  private ResolvedSelection resolveSelection(ModelSelection requested, AgentDefinition agent) {
    AgentModel model = modelRepository.getByName(requested.modelName());
    if (model != null) {
      return resolveLogical(model, requested.variant(), null);
    }
    String defaultModel = agent.modelName();
    AgentModel fallback =
        defaultModel == null || defaultModel.equals(requested.modelName())
            ? null
            : modelRepository.getByName(defaultModel);
    if (fallback == null) {
      throw rejection(
          "model "
              + requested.modelName()
              + " and the agent default model are both unavailable; bind an available model");
    }
    return resolveLogical(fallback, requested.variant(), agent.variant());
  }

  /**
   * 单 Model 按偏好依次取第一个由 config 声明的 variant（{@code preferredVariant}、{@code secondaryVariant}、默认
   * variant）；GROUP 没有组级 variant，一次性冻结组版本与有序成员。
   */
  private ResolvedSelection resolveLogical(
      AgentModel model, String preferredVariant, String secondaryVariant) {
    if (model.kind() == AgentModelKind.GROUP) {
      if (preferredVariant != null) {
        throw rejection("model group must not select a variant: " + model.name());
      }
      return resolveGroup(model);
    }
    ParsedAgentModelConfig parsed = parseModel(model);
    String variantName =
        findVariant(parsed, preferredVariant) != null
            ? preferredVariant
            : findVariant(parsed, secondaryVariant) != null
                ? secondaryVariant
                : parsed.defaultVariant();
    MemberRuntime member = resolveMember(model, variantName);
    return new ResolvedSelection(
        member.descriptor(), contextWindow(member.parsed()), List.of(member), null);
  }

  /** 冻结组版本、有序成员及其 Provider/连接代际/Descriptor/Variant：组能力取成员输入模态的交集、上下文取最小值，成员切换绝不重新读取 Catalog。 */
  private ResolvedSelection resolveGroup(AgentModel groupModel) {
    AgentModelGroupDTO group = groupConfigCodec.decode(groupModel.groupConfigJson());
    List<MemberRuntime> members = new ArrayList<>(group.getMembers().size());
    List<ModelGroupMemberExecution> executions = new ArrayList<>(group.getMembers().size());
    Set<ModelInputModality> modalities = null;
    int contextWindow = Integer.MAX_VALUE;
    for (AgentModelGroupMemberDTO member : group.getMembers()) {
      AgentModel memberModel =
          require(
              modelRepository.getByName(member.getModelName()),
              "group member model not found: " + member.getModelName());
      if (memberModel.kind() != AgentModelKind.MODEL) {
        throw rejection("group member is not a single model: " + member.getModelName());
      }
      MemberRuntime runtime = resolveMember(memberModel, member.getVariant());
      members.add(runtime);
      executions.add(
          new ModelGroupMemberExecution(
              runtime.providerType(),
              runtime.providerConnectionGenerationId(),
              runtime.descriptor(),
              runtime.variant()));
      if (modalities == null) {
        modalities = new LinkedHashSet<>(runtime.descriptor().inputModalities());
      } else {
        modalities.retainAll(runtime.descriptor().inputModalities());
      }
      contextWindow = Math.min(contextWindow, contextWindow(runtime.parsed()));
    }
    if (modalities == null || modalities.isEmpty()) {
      throw rejection("model group members have no common input modality: " + groupModel.name());
    }
    ModelDescriptor first = members.getFirst().descriptor();
    ModelDescriptor groupDescriptor =
        new ModelDescriptor(
            first.providerName(),
            groupModel.name(),
            first.modelId(),
            modalities,
            first.tools(),
            first.reasoning());
    ModelGroupExecution execution =
        new ModelGroupExecution(
            groupModel.name(),
            groupModel.version(),
            executions,
            new LinkedHashSet<>(group.getFallbackStatusCodes()),
            group.getCooldownSeconds());
    return new ResolvedSelection(groupDescriptor, contextWindow, members, execution);
  }

  /** 解析单 Model 的执行身份；{@code variantName} 必须由该 Model 的 config 声明。 */
  private MemberRuntime resolveMember(AgentModel model, String variantName) {
    AgentProvider provider =
        require(
            providerRepository.getByName(model.providerName()),
            "provider not found: " + model.providerName());
    ProviderType providerType = provider.providerType();
    if (providerType == null) {
      throw rejection("provider type must not be null");
    }
    UUID providerConnectionGenerationId =
        require(
            provider.connectionGenerationId(),
            "provider connection generation id must not be null");
    ProviderFactory factory =
        require(
            providerFactories.lookup(providerType).orElse(null),
            "provider factory not found for " + provider.name() + " (" + providerType + ")");
    ParsedAgentModelConfig parsed = parseModel(model);
    ModelVariant variant = findVariant(parsed, variantName);
    if (variant == null) {
      throw rejection("model variant not found: " + model.name() + " variant=" + variantName);
    }
    ModelDescriptor descriptor =
        new ModelDescriptor(
            model.providerName(),
            model.name(),
            model.modelId(),
            parsed.inputModalities(),
            parsed.tools(),
            parsed.reasoning());
    return new MemberRuntime(
        model.name(),
        provider,
        providerType,
        providerConnectionGenerationId,
        factory,
        parsed,
        descriptor,
        variant);
  }

  private static void requireReasoningSupport(MemberRuntime member) {
    if (member.variant().reasoningEffort() != null && !member.parsed().reasoning()) {
      throw rejection("model does not support reasoning: " + member.modelName());
    }
  }

  /**
   * 压缩 resolver 路径：只按 preparation 的 executionModel 查找模型、Provider 与 Variant 构造请求——不查 Agent system
   * prompt、不查 contributors、零 tool/skill、不做 environment 可用性查找、不启用 cache（{@code
   * ProviderCacheControl.none()}）。切分事实由 candidate path 的 compaction TURN_START 持有，不复制进 spec；输出预算按当前
   * config 与 removedPrefixTokens 现算。
   */
  private Result resolveCompaction(EntryPath parentPath, CompactionPreparation preparation) {
    String compactionAgentName = BuiltinAgentDefinitions.COMPACTION_NAME;
    AgentDefinition agent =
        require(
            agentDefinitionRepository.getByName(compactionAgentName),
            "agent not found: " + compactionAgentName);
    if (agent.modelName() == null) {
      throw rejection("compaction agent has no configured model");
    }
    AgentModel model =
        require(
            modelRepository.getByName(agent.modelName()), "model not found: " + agent.modelName());
    ResolvedSelection resolved = resolveLogical(model, agent.variant(), null);
    for (MemberRuntime member : resolved.members()) {
      requireReasoningSupport(member);
    }
    ModelSelection compactorModel =
        resolved.group() == null
            ? ModelSelection.model(model.name(), resolved.members().getFirst().variant().id())
            : ModelSelection.group(model.name());
    int maxOutputTokens =
        resolved.members().stream()
            .mapToInt(member -> outputTokens(member.parsed()))
            .min()
            .orElseThrow();
    long outputBudget =
        compactionConfigProvider
            .compactionConfig()
            .outputBudget(preparation.phase(), maxOutputTokens, preparation.removedPrefixTokens());
    if (outputBudget <= 0L || outputBudget > Integer.MAX_VALUE) {
      throw rejection("compaction output budget must be a positive int, got " + outputBudget);
    }
    BranchSettings childSettings =
        new BranchSettings(
            compactionAgentName, compactorModel, parentPath.baseSettings().environmentName(), null);
    return new TurnResolver.CompactionResolved(
        compactorModel, outputBudget, resolved.contextWindow(), maxOutputTokens, childSettings);
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

  private ParsedAgentModelConfig parseModel(AgentModel model) {
    try {
      return modelConfigParser.parse(model.configJson());
    } catch (IllegalArgumentException error) {
      throw rejection("invalid model configuration: " + messageOrClass(error));
    }
  }

  private static ModelVariant findVariant(ParsedAgentModelConfig model, String name) {
    return model.variants().stream()
        .filter(variant -> variant.id().equals(name))
        .findFirst()
        .orElse(null);
  }

  /** Provider 当前配置下的提示缓存留存档位；配置非法或解析失败时确定性拒绝规划（绝不静默降级或不缓存）。 */
  private static PromptCacheRetention promptCacheRetention(
      ProviderFactory providerFactory, String configJson, String providerName) {
    PromptCacheRetention retention;
    try {
      retention = providerFactory.promptCacheRetention(configJson);
    } catch (IllegalArgumentException error) {
      // 只把配置非法 / 解析失败当确定性拒绝；其它异常（含基础设施）照常传播，绝不伪装成 planning 失败。
      throw rejection("invalid prompt cache configuration for provider: " + providerName);
    }
    if (retention == null) {
      throw rejection("invalid prompt cache configuration for provider: " + providerName);
    }
    return retention;
  }

  /** 一个单 Model 的已解析执行身份；模型组的每个成员各有一份。 */
  private record MemberRuntime(
      String modelName,
      AgentProvider provider,
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ProviderFactory factory,
      ParsedAgentModelConfig parsed,
      ModelDescriptor descriptor,
      ModelVariant variant) {}

  /** 逻辑模型的解析结果：单 Model 只有一个成员且 {@code group} 为 null；模型组按序列出全部成员与冻结的组执行快照。 */
  private record ResolvedSelection(
      ModelDescriptor descriptor,
      int contextWindow,
      List<MemberRuntime> members,
      ModelGroupExecution group) {}

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
