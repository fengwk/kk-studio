package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

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
import fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgents;
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
        return resolveCompaction(compactionPreparation);
      }
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(rootSessionId, "rootSessionId");
      return resolved(plan(threadId, rootSessionId, path, path.baseSettings()));
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
    return new LiveTurnPlan.Planned(
        new ModelRequestSpec(
            first.providerType(),
            resolved.descriptor(),
            first.variant(),
            outputTokens,
            systemInstruction,
            context.toolBindings(),
            context.subagentBindings(),
            resolved.group(),
            context.systemSections()),
        contextWindow,
        context.candidateTools(),
        context.skills());
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

  /** 冻结组版本、有序成员及其 Provider/Descriptor/Variant：组能力取成员输入模态的交集、上下文取最小值，成员切换绝不重新读取 Catalog。 */
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
              runtime.providerType(), runtime.descriptor(), runtime.variant()));
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
            first.midConversationSystemMessages(),
            first.messageLevelEffort());
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
            parsed.midConversationSystemMessages(),
            parsed.messageLevelEffort());
    return new MemberRuntime(model.name(), provider, providerType, parsed, descriptor, variant);
  }

  private static void requireReasoningSupport(MemberRuntime member) {
    if (member.variant().reasoningEffort() != null && !member.parsed().reasoning()) {
      throw rejection("model does not support reasoning: " + member.modelName());
    }
  }

  /**
   * 压缩 resolver 路径：只按 preparation 的 executionModel 查找模型、Provider 与 Variant 构造请求——不查 Agent system
   * prompt、不查 contributors、零 tool/skill、不做 environment 可用性查找。压缩使用 Compaction Agent 自身配置的模型与
   * variant。 切分事实由 candidate path 的 compaction TURN_START 持有，不复制进 spec；输出预算按当前 config 与
   * removedPrefixTokens 现算。
   */
  private Result resolveCompaction(CompactionPreparation preparation) {
    String compactionAgentName = BuiltinAgents.COMPACTION_NAME;
    Agent agent =
        require(
            agentRepository.getByName(compactionAgentName),
            "agent not found: " + compactionAgentName);
    if (agent.execution() == null) {
      throw rejection("compaction agent has no configured model");
    }
    if (!BuiltinExecution.DRIVER.equals(agent.execution().driver())) {
      throw rejection("compaction agent must use builtin execution");
    }
    ModelSelection selection = builtinAgentModel(agent);
    Model model =
        require(
            modelRepository.getByName(selection.modelName()),
            "model not found: " + selection.modelName());
    ResolvedSelection resolved = resolveLogical(model, selection.variant(), null);
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
    int outputBudget;
    try {
      outputBudget =
          BuiltinModelBudget.compactionOutputBudget(
              compactionConfigProvider.compactionConfig(),
              preparation.phase(),
              maxOutputTokens,
              preparation.removedPrefixTokens());
    } catch (IllegalArgumentException error) {
      throw rejection(error.getMessage());
    }
    ExecutionSelection compactorExecution = BuiltinExecution.of(compactorModel);
    BranchSettings childSettings =
        new BranchSettings(compactionAgentName, compactorExecution, null);
    return new TurnResolver.CompactionResolved(
        compactorExecution, outputBudget, resolved.contextWindow(), maxOutputTokens, childSettings);
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
