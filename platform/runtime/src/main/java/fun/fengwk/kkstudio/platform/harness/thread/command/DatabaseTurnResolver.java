package fun.fengwk.kkstudio.platform.harness.thread.command;

import lombok.extern.slf4j.Slf4j;
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
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.spi.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionModelProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinModelBudget;
import fun.fengwk.kkstudio.platform.harness.thread.command.TurnModelSelectionResolver.MemberRuntime;
import fun.fengwk.kkstudio.platform.harness.thread.command.TurnModelSelectionResolver.SelectionSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 生产 Platform 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的
 * {@link ModelRequestSpec}、contextWindow 与 outputTokens。
 *
 * <p>本解析器<strong>不反查 Thread 归属</strong>：Agent、Model、Environment、工具与身份上下文都由 branch state 和运行时配置提供，所有
 * Thread 都走同一条 branch 事实解析路径。领域专属上下文由相应 contributor state 的 context projector 与运行期工具在各自事务边界内校验。
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
 *
 * <p>模型选择解析由 {@link TurnModelSelectionResolver} 承担，预算适配由 {@link TurnModelBudget} 承担，system message
 * 投影由 {@link TurnSystemMessageProjector} 承担；本类只保留入口编排、压缩判定与 typed 拒绝投影。
 */
@Slf4j
@Component
public final class DatabaseTurnResolver implements TurnResolver {

  /** 所有确定性拒绝共用的稳定 AssistantError code。 */
  public static final String REJECTION_CODE = "PLANNING_FAILED";

  private final AgentRepository agentRepository;
  private final TurnModelSelectionResolver selectionResolver;
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
    this.selectionResolver =
        new TurnModelSelectionResolver(
            modelRepository,
            providerRepository,
            modelConfigParser,
            groupConfigCodec,
            providerFactories);
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
    } catch (TurnRejection | AgentTurnContextException rejection) {
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
    } catch (TurnRejection | AgentTurnContextException rejection) {
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
    } catch (TurnRejection | AgentTurnContextException rejection) {
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
    SelectionSnapshot resolved =
        selectionResolver.resolve(BuiltinExecution.model(settings.execution()), context.agent());
    MemberRuntime first = resolved.members().getFirst();

    for (MemberRuntime member : resolved.members()) {
      if (!context.toolBindings().isEmpty() && !member.parsed().tools()) {
        throw TurnRejection.of("model does not support tools: " + member.modelName());
      }
      requireReasoningSupport(member);
    }

    String systemInstruction = context.systemInstruction();
    int contextWindow = resolved.contextWindow();
    List<String> requestSystemMessages =
        TurnSystemMessageProjector.project(
            path, context, first.providerType(), resolved.descriptor());
    long estimatedInputTokens =
        requestTokenEstimator.estimateRequestTokens(path, requestSystemMessages);
    // 组内每个成员都必须容纳这次请求：输出预算取所有成员可提供预算的最小值。
    int outputTokens =
        resolved.members().stream()
            .mapToInt(
                member ->
                    TurnModelBudget.outputTokens(
                        member.parsed(),
                        TurnModelBudget.contextWindow(member.parsed()),
                        estimatedInputTokens))
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
            context.skills()),
        estimatedInputTokens,
        conversationMaxOutputTokens,
        contextWindow);
  }

  private static void requireReasoningSupport(MemberRuntime member) {
    if (member.variant().reasoningEffort() != null && !member.parsed().reasoning()) {
      throw TurnRejection.of("model does not support reasoning: " + member.modelName());
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
    // 回退是设计行为，但只在真正执行压缩时告知一次配置与原因；请求前窗口判断不得重复刷日志。
    ModelSelection configured = compactionModelProvider.compactionModel();
    SelectionSnapshot resolved = null;
    if (configured != null) {
      ConfiguredCompactionSelection candidate = resolveConfiguredSelection(configured);
      if (candidate.resolved() == null) {
        log.warn(
            "configured compaction model {} is unusable ({}); falling back to the conversation"
                + " model {}",
            configured,
            candidate.failureReason(),
            conversation);
      }
      resolved = candidate.resolved();
    }
    if (resolved == null) {
      resolved = selectionResolver.resolveExplicit(conversation);
    }
    for (MemberRuntime member : resolved.members()) {
      requireReasoningSupport(member);
    }
    ExecutionSelection compactorExecution =
        BuiltinExecution.of(TurnModelSelectionResolver.toSelection(resolved));
    int maxOutputTokens =
        resolved.members().stream()
            .mapToInt(member -> TurnModelBudget.outputTokens(member.parsed()))
            .min()
            .orElseThrow();
    int outputBudget;
    try {
      outputBudget =
          BuiltinModelBudget.compactionOutputBudget(
              compactionConfigProvider.compactionConfig(), maxOutputTokens);
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of(error.getMessage());
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
    SelectionSnapshot resolved =
        selectionResolver.resolveExplicit(BuiltinExecution.model(settings.execution()));
    for (MemberRuntime member : resolved.members()) {
      requireReasoningSupport(member);
    }
    SystemContext inherited =
        path.systemContext()
            .orElseThrow(
                () -> TurnRejection.of("compaction branch has no inherited SYSTEM baseline"));
    String systemInstruction = inherited.systemInstruction();
    long estimatedInputTokens =
        requestTokenEstimator.estimateRequestTokens(path, List.of(systemInstruction));
    int outputTokens =
        resolved.members().stream()
            .mapToInt(
                member ->
                    TurnModelBudget.outputTokens(
                        member.parsed(),
                        TurnModelBudget.contextWindow(member.parsed()),
                        estimatedInputTokens))
            .min()
            .orElseThrow();
    MemberRuntime first = resolved.members().getFirst();
    // 消息级 effort 模型：压缩分支在摘要指令之后显式声明 low，仍然保持同一具体模型与 variant 身份。
    ModelVariant requestVariant =
        resolved.descriptor().reasoning() && resolved.descriptor().messageLevelEffort()
            ? new ModelVariant(
                first.variant().id(),
                "low",
                first.variant().promptCacheRetention(),
                first.variant().protocolOptions())
            : first.variant();
    return new TurnResolver.Resolved(
        new ModelRequestSpec(
            first.providerType(),
            resolved.descriptor(),
            requestVariant,
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

  /** 已配置压缩模型的选择或不可用原因：引用缺失、Provider 删除/停用、组带 variant 都只影响本次回退。 */
  private record ConfiguredCompactionSelection(SelectionSnapshot resolved, String failureReason) {}

  private ConfiguredCompactionSelection resolveConfiguredSelection(ModelSelection configured) {
    try {
      return new ConfiguredCompactionSelection(selectionResolver.resolveExplicit(configured), null);
    } catch (TurnRejection rejected) {
      return new ConfiguredCompactionSelection(null, rejected.getMessage());
    }
  }

  /** 已配置的压缩模型（仅用于请求前窗口判断；无效配置静默沿用对话模型窗口）。 */
  private SelectionSnapshot configuredCompactionSelection() {
    ModelSelection configured = compactionModelProvider.compactionModel();
    return configured == null ? null : resolveConfiguredSelection(configured).resolved();
  }

  /** 触发阈值使用的压缩模型窗口；无效配置沿用对话模型窗口，且不在这里记录回退日志。 */
  private int compactorContextWindow(int conversationContextWindow) {
    SelectionSnapshot configured = configuredCompactionSelection();
    return configured == null ? conversationContextWindow : configured.contextWindow();
  }

  /** 确定性拒绝：只由显式调用产生，绝不捕获外部基础设施异常。 */
  private static Result rejected(String message) {
    return new TurnResolver.Rejected(new AssistantError(REJECTION_CODE, message));
  }
}
