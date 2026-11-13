package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.spi.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionModelProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinModelBudget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 outputTokens。
 *
 * <p>本解析器<strong>不反查 Thread 归属</strong>：Issue Run 的 Agent、Model、Environment、业务工具与身份上下文都在接受 Run
 * 时被显式提交或冻结到 branch state，普通 Thread 与 Issue Agent Thread 走同一条纯 branch 事实解析路径。Issue 语义由 {@code
 * project/run} contributor state 的 context projector 与运行期业务工具在自己的事务边界内校验。
 *
 * <p>普通 branch 的输入事实只有 candidate path 的 {@link BranchSettings}（agentName / {@link ModelSelection} /
 * 可空 environmentName）。Agent 目录、Environment、tools/skills/subagents 与 system instruction 由 {@link
 * DatabaseAgentTurnContextResolver} 在一次 catalog 租用内解析为与模型无关的 {@link AgentTurnContext}；本类只在这份
 * 快照上解析模型组、variant、压缩、缓存与输出预算。目录快照的确定性拒绝在本边界投影为 {@link Result.Rejected}（稳定 error code {@value
 * #REJECTION_CODE}），SQL 访问异常统一以 {@link TurnResolveTransientException} 上抛，由 ThreadProcessor 回滚事务后
 * reschedule；其它编程 / 契约异常仍向上传播并按确定性失败处理。YOLO 不进入 spec。
 *
 * <p>请求输入 token 估算只经内核端口 {@link RequestTokenEstimator} 注入；实现由组合根绑定到正式发送所用的同一估算算法，本类不复制算法、 不 import
 * 内核实现类型，也不依赖 {@code HarnessRuntime} 门面，因此 Resolver 在 platform 独立测试上下文里始终可以装配。
 */
@Component
public final class DatabaseTurnResolver implements TurnResolver {

  /** 所有确定性拒绝共用的稳定 AssistantError code。 */
  public static final String REJECTION_CODE = "PLANNING_FAILED";

  private final AgentRepository agentRepository;
  private final ModelRepository modelRepository;
  private final ProviderRepository providerRepository;
  private final ModelRuntimeConfigParser modelConfigParser;
  private final ModelGroupConfigCodec groupConfigCodec;
  private final Map<ProviderType, ProviderFactory> providerFactories;
  private final CompactionConfigProvider compactionConfigProvider;
  private final CompactionModelProvider compactionModelProvider;
  private final DatabaseAgentTurnContextResolver contextResolver;
  private final RequestTokenEstimator requestTokenEstimator;

  public DatabaseTurnResolver(
      AgentRepository agentRepository,
      ModelRepository modelRepository,
      ProviderRepository providerRepository,
      ModelRuntimeConfigParser modelConfigParser,
      ModelGroupConfigCodec groupConfigCodec,
      Map<ProviderType, ProviderFactory> providerFactories,
      CompactionConfigProvider compactionConfigProvider,
      CompactionModelProvider compactionModelProvider,
      DatabaseAgentTurnContextResolver contextResolver,
      RequestTokenEstimator requestTokenEstimator) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.groupConfigCodec = Objects.requireNonNull(groupConfigCodec, "groupConfigCodec");
    this.providerFactories =
        Map.copyOf(Objects.requireNonNull(providerFactories, "providerFactories"));
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.compactionModelProvider =
        Objects.requireNonNull(compactionModelProvider, "compactionModelProvider");
    this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
    this.requestTokenEstimator =
        Objects.requireNonNull(requestTokenEstimator, "requestTokenEstimator");
  }

  @Override
  public Result resolve(
      UUID threadId,
      UUID rootSessionId,
      EntryPath path,
      CompactionPreparation compactionPreparation) {
    Objects.requireNonNull(path, "path");
    try {
      if (compactionPreparation != null) {
        return resolveCompaction(path.baseSettings(), compactionPreparation);
      }
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(rootSessionId, "rootSessionId");
      RequestPlan request = planRequest(threadId, rootSessionId, path, path.baseSettings());
      LiveTurnPlan.Planned planned = request.planned();
      CompactionConfig compaction = compactionConfigProvider.compactionConfig();
      long reserve = compaction.effectiveReserve(request.conversationMaxOutputTokens());
      long budget =
          Math.min(
              planned.contextWindow(), compactorContextWindow(request.conversationContextWindow()));
      if (request.estimatedInputTokens() + reserve > budget) {
        return new CompactionRequired(planned.contextWindow());
      }
      return resolved(planned);
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

  @Override
  public Result resolveCompactionBranch(UUID threadId, UUID rootSessionId, EntryPath path) {
    Objects.requireNonNull(path, "path");
    try {
      return resolveCompactionBranch(path);
    } catch (Rejection | AgentTurnContextException rejection) {
      return rejected(rejection.getMessage());
    } catch (DataAccessException failure) {
      throw new TurnResolveTransientException(
          "turn resolution infrastructure unavailable", failure);
    }
  }

  /**
   * 只读现算 live 规划：与 {@link #resolve} 共用同一条 Agent/Environment/Tool/Skill 解析纯函数，供 Debug 预览直接消费。
   *
   * <p>{@code rootSessionId} 是目标 Thread 的执行根会话；确定性拒绝被投影为 {@link LiveTurnPlan.Rejected}（稳定 {@value
   * #REJECTION_CODE}）；repository / registry / projector 等基础设施或编程异常照常传播， 绝不伪装成 planning
   * 失败。压缩路径不属于本入口。
   */
  public LiveTurnPlan planLive(UUID threadId, UUID rootSessionId, EntryPath path) {
    return planLive(threadId, rootSessionId, path, path.baseSettings());
  }

  /**
   * 显式 settings 的只读 live 规划：与正式入口共用同一个 planner，只把 draft 的 Agent/Model/Environment 快照换成调用方给出的 {@link
   * BranchSettings}；EntryPath 本身不承载、也不被写入任何 draft。
   *
   * <p>供 Debug 预览以 UI 当前草稿选择现算，绝不改变 Thread 的持久事实：不落 Entry、不动 cursor、不做 CAS、不发布 Package、也不触发 Daemon
   * sync。
   */
  public LiveTurnPlan planLiveWithSettings(
      UUID threadId, UUID rootSessionId, EntryPath path, BranchSettings settings) {
    return planLive(threadId, rootSessionId, path, settings);
  }

  private LiveTurnPlan planLive(
      UUID threadId, UUID rootSessionId, EntryPath path, BranchSettings settings) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(rootSessionId, "rootSessionId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(settings, "settings");
    try {
      return plan(threadId, rootSessionId, path, settings);
    } catch (Rejection | AgentTurnContextException rejection) {
      return new LiveTurnPlan.Rejected(REJECTION_CODE, rejection.getMessage());
    }
  }

  /**
   * 历史模型输出的只读规划：请求前缀是 ROOT 到该输出的 parent。
   *
   * <p>普通输出与 live 共用同一 planner。父 COMPACTION turn 自身不创建 ModelInvocation：它只冻结 run group 与输出预算，真实
   * provider 请求属于独立的压缩子 Thread（子请求按父冻结预算收紧），因此这里绝不伪造一份零工具请求，而是返回稳定 typed 拒绝。
   */
  public LiveTurnPlan planHistorical(UUID threadId, UUID rootSessionId, EntryPath path) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(rootSessionId, "rootSessionId");
    Objects.requireNonNull(path, "path");
    if (isCompactionParentTurnStart(path.head())) {
      return new LiveTurnPlan.Rejected(
          REJECTION_CODE,
          "a COMPACTION parent turn has no model invocation; its provider request belongs to the"
              + " compaction child thread");
    }
    return planLive(threadId, rootSessionId, path);
  }

  /** 请求头是否是父 COMPACTION turn 的 TURN_START：该回合只冻结 run group 与预算，从不发出 provider 请求。 */
  private static boolean isCompactionParentTurnStart(Entry head) {
    return head.payload() instanceof TurnStartPayload start
        && start.reason() == TurnStartReason.COMPACTION;
  }

  private static TurnResolver.Resolved resolved(LiveTurnPlan.Planned plan) {
    return new TurnResolver.Resolved(plan.spec(), plan.contextWindow(), plan.spec().outputTokens());
  }

  private LiveTurnPlan.Planned plan(
      UUID threadId, UUID rootSessionId, EntryPath path, BranchSettings settings) {
    return planRequest(threadId, rootSessionId, path, settings).planned();
  }

  /** 普通请求的完整规划事实：冻结 spec 与触发阈值判定所需的输入/输出估算。 */
  private record RequestPlan(
      LiveTurnPlan.Planned planned,
      long estimatedInputTokens,
      long conversationMaxOutputTokens,
      int conversationContextWindow) {}

  private RequestPlan planRequest(
      UUID threadId, UUID rootSessionId, EntryPath path, BranchSettings settings) {
    // 一次上下文解析租用一份 catalog 快照：Agent/Environment/tools/skills/subagents/prompt 同源。
    AgentTurnContext context = contextResolver.resolve(threadId, rootSessionId, path, settings);
    ResolvedSelection resolved =
        resolveSelection(BuiltinExecution.model(settings.execution()), context.agent());
    MemberRuntime first = resolved.members().getFirst();

    for (MemberRuntime member : resolved.members()) {
      if (!context.toolBindings().isEmpty() && !member.parsed().tools()) {
        throw rejection("model does not support tools: " + member.modelName());
      }
      requireReasoningSupport(member);
    }

    String systemInstruction = context.systemInstruction();
    int contextWindow = resolved.contextWindow();
    List<String> requestSystemMessages =
        requestSystemMessages(path, context, first.providerType(), resolved.descriptor());
    long estimatedInputTokens =
        requestTokenEstimator.estimateRequestTokens(path, requestSystemMessages);
    // 组内每个成员都必须容纳这次请求：输出预算取所有成员可提供预算的最小值。
    int outputTokens =
        resolved.members().stream()
            .mapToInt(
                member ->
                    outputTokens(
                        member.parsed(), contextWindow(member.parsed()), estimatedInputTokens))
            .min()
            .orElseThrow();
    long conversationMaxOutputTokens =
        resolved.members().stream()
            .mapToLong(member -> member.parsed().maxOutputTokens())
            .min()
            .orElseThrow();
    return new RequestPlan(
        new LiveTurnPlan.Planned(
            new ModelRequestSpec(
                first.providerType(),
                first.providerConnectionGenerationId(),
                resolved.descriptor(),
                first.variant(),
                first.parsed().variants(),
                outputTokens,
                systemInstruction,
                context.toolBindings(),
                context.subagentBindings(),
                resolved.group(),
                context.systemSections()),
            contextWindow,
            context.candidateTools(),
            context.skills()),
        estimatedInputTokens,
        conversationMaxOutputTokens,
        contextWindow);
  }

  private static List<String> requestSystemMessages(
      EntryPath path, AgentTurnContext context, ProviderType providerType, ModelDescriptor model) {
    String currentBaseline = String.join("\n\n", context.systemSections().values());
    if (path.requiresSystemBaseline()) {
      return List.of(currentBaseline);
    }
    SystemContext existing = path.systemContext().orElse(null);
    if (existing == null) {
      return List.of(currentBaseline);
    }
    SystemContext next =
        new SystemContext(context.systemSections(), existing.tools(), existing.hostedTools());
    String pendingChange = existing.changesTo(next).map(SystemContext::renderChangeText).orElse("");
    boolean incrementalSystemMessages =
        providerType == ProviderType.ANTHROPIC && model.midConversationSystemMessages();
    if (!incrementalSystemMessages) {
      String collapsed = SystemContext.collapseSystemInstruction(path.entries());
      return List.of(pendingChange.isEmpty() ? collapsed : collapsed + "\n\n" + pendingChange);
    }

    List<String> messages = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (!(entry.payload() instanceof SystemPayload system)) {
        continue;
      }
      if (system.baseline()) {
        messages.clear();
        messages.add(String.join("\n\n", system.sections().values()));
      } else if (!messages.isEmpty()) {
        String changeText = SystemContext.renderChangeText(system);
        if (!changeText.isEmpty()) {
          messages.add(changeText);
        }
      }
    }
    if (!pendingChange.isEmpty()) {
      messages.add(pendingChange);
    }
    return List.copyOf(messages);
  }

  /**
   * 解析本 turn 实际调用的逻辑模型（MODEL 或 GROUP）：Thread 设置的模型存在时使用它；已从 catalog 移除时改用当前 Agent 的默认模型；二者都不可用时拒绝本
   * turn。
   *
   * <p>Thread 设置不被改写：默认模型恢复或重新绑定后，下一 turn 立即生效。单 Model 的 variant 优先沿用原设置；目标模型没有该 variant
   * 时，原模型改用自身的默认 variant，回退模型依次取 Agent 声明的 variant 与模型自身的默认 variant。只对模型回退，Agent 与 Environment
   * 失效一律停止。
   */
  private ResolvedSelection resolveSelection(ModelSelection requested, Agent agent) {
    Model model = modelRepository.getByName(requested.modelName());
    if (model != null) {
      return resolveLogical(model, requested.variant(), null);
    }
    ModelSelection defaultSelection = builtinAgentModel(agent);
    String defaultModel = defaultSelection == null ? null : defaultSelection.modelName();
    Model fallback =
        defaultModel == null || defaultModel.equals(requested.modelName())
            ? null
            : modelRepository.getByName(defaultModel);
    if (fallback == null) {
      throw rejection(
          "model "
              + requested.modelName()
              + " and the agent default model are both unavailable; bind an available model");
    }
    return resolveLogical(fallback, requested.variant(), defaultSelection.variant());
  }

  private static ModelSelection builtinAgentModel(Agent agent) {
    ExecutionSelection execution = agent.execution();
    if (execution == null || !BuiltinExecution.DRIVER.equals(execution.driver())) {
      return null;
    }
    try {
      return BuiltinExecution.model(execution);
    } catch (IllegalArgumentException error) {
      throw rejection(
          "invalid agent builtin execution: " + agent.name() + ": " + messageOrClass(error));
    }
  }

  /**
   * 单 Model 按偏好依次取第一个由 config 声明的 variant（{@code preferredVariant}、{@code secondaryVariant}、默认
   * variant）；GROUP 没有组级 variant，一次性冻结组版本与有序成员。
   */
  private ResolvedSelection resolveLogical(
      Model model, String preferredVariant, String secondaryVariant) {
    if (model.kind() == ModelKind.GROUP) {
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
  private ResolvedSelection resolveGroup(Model groupModel) {
    ModelGroupConfigDTO group = groupConfigCodec.decode(groupModel.groupConfigJson());
    List<MemberRuntime> members = new ArrayList<>(group.getMembers().size());
    List<ModelGroupMemberExecution> executions = new ArrayList<>(group.getMembers().size());
    Set<ModelInputModality> modalities = null;
    int contextWindow = Integer.MAX_VALUE;
    for (ModelGroupMemberDTO member : group.getMembers()) {
      Model memberModel =
          require(
              modelRepository.getByName(member.getModelName()),
              "group member model not found: " + member.getModelName());
      if (memberModel.kind() != ModelKind.MODEL) {
        throw rejection("group member is not a single model: " + member.getModelName());
      }
      MemberRuntime runtime = resolveMember(memberModel, member.getVariant());
      members.add(runtime);
      executions.add(
          new ModelGroupMemberExecution(
              runtime.providerType(),
              runtime.providerConnectionGenerationId(),
              runtime.descriptor(),
              runtime.variant(),
              runtime.parsed().variants()));
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
            first.reasoning(),
            first.midConversationSystemMessages());
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
  private MemberRuntime resolveMember(Model model, String variantName) {
    Provider provider =
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
    require(
        providerFactories.get(providerType),
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
            parsed.reasoning(),
            parsed.midConversationSystemMessages());
    return new MemberRuntime(
        model.name(),
        provider,
        providerType,
        providerConnectionGenerationId,
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
   * 压缩 owner turn：按 {@code aiRuntime.compactionModel} 解析压缩执行；未配置或配置无效时沿用对话当前模型与 variant。
   *
   * <p>配置有效但调用失败仍按普通重试耗尽后判定压缩失败，绝不换模型。摘要预算固定为模型输出上限 reserve 的 80%，切分事实由 candidate path 的 compaction
   * TURN_START 持有，不复制进 spec。
   */
  private Result resolveCompaction(
      BranchSettings conversationSettings, CompactionPreparation preparation) {
    Objects.requireNonNull(preparation, "preparation");
    ModelSelection conversation = BuiltinExecution.model(conversationSettings.execution());
    ResolvedSelection resolved = configuredCompactionSelection();
    if (resolved == null) {
      resolved = resolveLogicalModel(conversation);
    }
    for (MemberRuntime member : resolved.members()) {
      requireReasoningSupport(member);
    }
    ExecutionSelection compactorExecution = BuiltinExecution.of(compactorSelection(resolved));
    int maxOutputTokens =
        resolved.members().stream()
            .mapToInt(member -> outputTokens(member.parsed()))
            .min()
            .orElseThrow();
    int outputBudget;
    try {
      outputBudget =
          BuiltinModelBudget.compactionOutputBudget(
              compactionConfigProvider.compactionConfig(), maxOutputTokens);
    } catch (IllegalArgumentException error) {
      throw rejection(error.getMessage());
    }
    // 分支继承 owner Agent 身份；压缩执行模型独立，环境与工具面来自切点处的 SYSTEM 基线而不是现场解析。
    BranchSettings childSettings =
        new BranchSettings(conversationSettings.agentName(), compactorExecution, null);
    return new TurnResolver.CompactionResolved(
        compactorExecution, outputBudget, resolved.contextWindow(), maxOutputTokens, childSettings);
  }

  /**
   * 压缩分支自身的请求：SYSTEM instruction 与工具定义继承切点处的基线，模型来自分支冻结执行。
   *
   * <p>分支不重新解析 Agent/Environment，也不执行工具；工具调用由 Driver 以固定错误拒绝。分支超出压缩模型窗口时直接拒绝，压缩失败。
   */
  private Result resolveCompactionBranch(EntryPath path) {
    BranchSettings settings = path.baseSettings();
    ResolvedSelection resolved = resolveLogicalModel(BuiltinExecution.model(settings.execution()));
    for (MemberRuntime member : resolved.members()) {
      requireReasoningSupport(member);
    }
    SystemContext inherited =
        path.systemContext()
            .orElseThrow(() -> rejection("compaction branch has no inherited SYSTEM baseline"));
    String systemInstruction = inherited.systemInstruction();
    long estimatedInputTokens =
        requestTokenEstimator.estimateRequestTokens(path, List.of(systemInstruction));
    int outputTokens =
        resolved.members().stream()
            .mapToInt(
                member ->
                    outputTokens(
                        member.parsed(), contextWindow(member.parsed()), estimatedInputTokens))
            .min()
            .orElseThrow();
    MemberRuntime first = resolved.members().getFirst();
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            first.providerType(),
            first.providerConnectionGenerationId(),
            resolved.descriptor(),
            first.variant(),
            first.parsed().variants(),
            outputTokens,
            systemInstruction,
            // 分支工具面来自切点 SYSTEM 基线（SystemContext.tools），不需要也不应重新冻结 ToolBinding。
            List.of(),
            List.of(),
            resolved.group(),
            inherited.sections()),
        resolved.contextWindow(),
        outputTokens);
  }

  /** 已配置的压缩模型；未配置或无法解析（引用缺失、Provider 删除/停用、组带 variant）时返回 null 由调用方回退对话模型。 */
  private ResolvedSelection configuredCompactionSelection() {
    ModelSelection configured = compactionModelProvider.compactionModel();
    if (configured == null) {
      return null;
    }
    try {
      return resolveLogicalModel(configured);
    } catch (Rejection ignored) {
      return null;
    }
  }

  /** 触发阈值使用的压缩模型窗口；无效配置沿用对话模型窗口。 */
  private int compactorContextWindow(int conversationContextWindow) {
    ResolvedSelection configured = configuredCompactionSelection();
    return configured == null ? conversationContextWindow : configured.contextWindow();
  }

  /** 按显式选择解析单个 MODEL 或 MODEL_GROUP；GROUP 必须不带 variant。 */
  private ResolvedSelection resolveLogicalModel(ModelSelection selection) {
    Objects.requireNonNull(selection, "selection");
    Model model =
        require(
            modelRepository.getByName(selection.modelName()),
            "model not found: " + selection.modelName());
    return resolveLogical(model, selection.variant(), null);
  }

  private static ModelSelection compactorSelection(ResolvedSelection resolved) {
    return resolved.group() == null
        ? ModelSelection.model(
            resolved.descriptor().modelName(), resolved.members().getFirst().variant().id())
        : ModelSelection.group(resolved.descriptor().modelName());
  }

  /** Builtin 预算口径集中在 platform.harness.builtin；这里只把非法配置转换为确定性拒绝。 */
  private static int contextWindow(ParsedAgentModelConfig parsedModel) {
    try {
      return BuiltinModelBudget.contextWindow(parsedModel);
    } catch (IllegalArgumentException error) {
      throw rejection(error.getMessage());
    }
  }

  private static int outputTokens(
      ParsedAgentModelConfig parsedModel, int contextWindow, long estimatedInputTokens) {
    try {
      return BuiltinModelBudget.outputTokens(parsedModel, contextWindow, estimatedInputTokens);
    } catch (IllegalArgumentException error) {
      throw rejection(error.getMessage());
    }
  }

  private static int outputTokens(ParsedAgentModelConfig parsedModel) {
    try {
      return BuiltinModelBudget.outputTokens(parsedModel);
    } catch (IllegalArgumentException error) {
      throw rejection(error.getMessage());
    }
  }

  private ParsedAgentModelConfig parseModel(Model model) {
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

  /** 一个单 Model 的已解析执行身份；模型组的每个成员各有一份。 */
  private record MemberRuntime(
      String modelName,
      Provider provider,
      ProviderType providerType,
      UUID providerConnectionGenerationId,
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
