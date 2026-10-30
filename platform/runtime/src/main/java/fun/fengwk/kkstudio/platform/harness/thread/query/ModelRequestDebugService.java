package fun.fengwk.kkstudio.platform.harness.thread.query;

import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery;
import fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestView;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread Debug 的结构化模型请求投影：一次不可变 snapshot 上加一次共享规划。
 *
 * <p>顶层是现算的 {@code NEXT_REQUEST_PREVIEW}：model / environment 来自本次请求的草稿选择，其余事实来自 {@link
 * DatabaseTurnResolver#planLiveWithSettings} 的只读规划结果——与正式 Turn 完全同源，因此预览不可能与真实请求漂移。草稿选择只覆盖 model 与
 * environment，Agent 引用与 Goal 仍取自 branch settings；预览不检查 branch HEAD、不做 CAS、不发布 Package、不触发 Daemon
 * sync，也不产生任何写入。
 *
 * <p>确定性 planning 拒绝只回显稳定 error code，并给出安全的空投影；自由文本拒绝详情绝不外泄。repository / registry / projector
 * 等基础设施或编程异常照常传播，绝不伪装成 planning 失败。
 *
 * <p>只要 snapshot 暴露活动 Driver generation，就独立附加可空的 {@code FROZEN_INVOCATION}：它直接使用 Driver 冻结的
 * canonical request-spec 诊断 JSON，不再读取任何 Provider DTO 或 Driver 私有状态。冻结事实只来自原始 snapshot，与草稿 model /
 * environment 无关，也不新建、复制或延长任何持久事实的寿命。
 *
 * <p>冻结请求视图与顶层现算预览相互独立——预览被拒绝时冻结事实仍然成立。
 *
 * <p>每个 subagent binding 额外附带目标 Agent 当前配置声明（tools/skills/subagents 与 canonical config JSON）：它只读
 * {@link AgentDefinitionRepository} 与 {@link AgentDefinitionConfigCodec}，同名缺失或配置非法时明确拒绝，绝不静默返回空数组，
 * 也不递归展开下一层 Agent。
 *
 * <p>Platform 不是 Harness 组合根，本服务由 Web 组合根在 {@link HarnessRuntime} 完整装配后显式创建。
 */
public final class ModelRequestDebugService {

  /** 现算预览的视图判别符。 */
  public static final String PREVIEW_KIND = "NEXT_REQUEST_PREVIEW";

  /** 活动 ModelInvocation 冻结请求的视图判别符。 */
  public static final String FROZEN_INVOCATION_KIND = "FROZEN_INVOCATION";

  private static final ModelRequestSpecJsonCodec BUILTIN_REQUESTS = new ModelRequestSpecJsonCodec();

  private final HarnessRuntime runtime;
  private final DatabaseTurnResolver turnResolver;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final BuiltinRequestQuery builtinRequests;
  private final Clock clock;

  public ModelRequestDebugService(
      HarnessRuntime runtime,
      DatabaseTurnResolver turnResolver,
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      BuiltinRequestQuery builtinRequests,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.turnResolver = Objects.requireNonNull(turnResolver, "turnResolver");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.builtinRequests = Objects.requireNonNull(builtinRequests, "builtinRequests");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 按草稿 model / environment 现算一次结构化 Debug 投影；不存在的 Thread 由 {@link HarnessRuntime} 以 typed 异常拒绝。
   *
   * <p>{@code environmentName} 为 null 表示草稿未选择 Environment；草稿只替换真实 base settings 的 model 与
   * environment，保留 Agent 引用与 Goal，且绝不写回 EntryPath。
   */
  public HarnessModelRequestDebugDTO getModelRequestDebug(
      UUID threadId, ModelSelection model, String environmentName) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(model, "model");
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    EntryPath path = snapshot.entryPath();
    BranchSettings draftSettings =
        path.baseSettings().withModel(model).withEnvironmentName(environmentName);

    HarnessModelRequestDebugDTO dto = new HarnessModelRequestDebugDTO();
    dto.setKind(PREVIEW_KIND);
    dto.setGeneratedAt(clock.instant());
    dto.setModel(modelSelection(model));
    dto.setEnvironmentName(environmentName);
    LiveTurnPlan plan = turnResolver.planLiveWithSettings(threadId, path, draftSettings);
    if (plan instanceof LiveTurnPlan.Planned planned) {
      project(dto, planned);
    } else {
      projectPlanningError(dto, (LiveTurnPlan.Rejected) plan);
    }
    // 冻结请求与预览相互独立：当前预览被拒绝时活动 Invocation 的冻结事实仍然成立。
    dto.setFrozenInvocation(frozenInvocation(threadId));
    return dto;
  }

  private void project(HarnessModelRequestDebugDTO dto, LiveTurnPlan.Planned planned) {
    ModelRequestSpec spec = planned.spec();
    dto.setSystemInstruction(spec.systemInstruction());
    dto.setTools(planned.candidateTools().stream().map(ModelRequestDebugService::tool).toList());
    dto.setSkills(planned.skills().stream().map(ModelRequestDebugService::skill).toList());
    dto.setSubagents(spec.subagentBindings().stream().map(this::subagent).toList());
    dto.setCacheControl(cacheControl(spec.cacheControl()));
    dto.setPlanningError(null);
  }

  /** 规划拒绝：只回显稳定 code，并给出安全的空投影，绝不回显自由文本详情。 */
  private static void projectPlanningError(
      HarnessModelRequestDebugDTO dto, LiveTurnPlan.Rejected rejected) {
    dto.setSystemInstruction("");
    dto.setTools(List.of());
    dto.setSkills(List.of());
    dto.setSubagents(List.of());
    dto.setCacheControl(null);
    dto.setPlanningError(rejected.errorCode());
  }

  private static HarnessModelRequestDebugDTO.ToolDTO tool(LiveTurnPlan.CandidateTool tool) {
    HarnessModelRequestDebugDTO.ToolDTO dto = new HarnessModelRequestDebugDTO.ToolDTO();
    dto.setName(tool.name());
    dto.setDescription(tool.description());
    dto.setInputSchemaJson(tool.inputSchemaJson());
    // Debug 与 Tool catalog 共用同一个 wire 枚举，两端名字一一对应。
    dto.setEnvironmentSupport(EnvironmentSupportDTO.valueOf(tool.environmentSupport().name()));
    dto.setRequiredEnvironmentId(
        tool.requiredEnvironmentId() == null ? null : tool.requiredEnvironmentId().toString());
    dto.setProvenance(tool.provenance());
    dto.setState(tool.state().name());
    dto.setFilterReason(tool.filterReason() == null ? null : tool.filterReason().name());
    return dto;
  }

  private static HarnessModelRequestDebugDTO.SkillDTO skill(LiveTurnPlan.PlannedSkill skill) {
    HarnessModelRequestDebugDTO.SkillDTO dto = new HarnessModelRequestDebugDTO.SkillDTO();
    dto.setPackageName(skill.packageName());
    dto.setName(skill.name());
    dto.setDescription(skill.description());
    dto.setPath(skill.path());
    dto.setDelivery(skill.delivery().name());
    dto.setCurrentCommit(skill.currentCommit());
    dto.setObservedHeadCommit(skill.observedHeadCommit());
    dto.setInstalledCommit(skill.installedCommit());
    // 与 compose 共用同一个渲染入口：展示的 XML 就是实际进入 systemInstruction 的片段。
    dto.setPromptXml(AgentPromptComposer.skillFragment(skill.promptEntry()));
    return dto;
  }

  /**
   * subagent binding 事实（name/description）来自本次规划的冻结 spec；当前配置声明再按同名 Agent definition 只读补齐。
   *
   * <p>声明读取时同名定义缺失或配置非法必须明确失败，不静默返回空配置。
   */
  private HarnessModelRequestDebugDTO.SubagentDTO subagent(SubagentBinding binding) {
    AgentDefinition definition = agentDefinitionRepository.getByName(binding.name());
    if (definition == null) {
      throw new IllegalStateException("subagent definition not found: " + binding.name());
    }
    AgentDefinitionConfigDTO config = decodeSubagentConfig(definition);
    HarnessModelRequestDebugDTO.SubagentDTO dto = new HarnessModelRequestDebugDTO.SubagentDTO();
    dto.setName(binding.name());
    dto.setDescription(binding.description());
    dto.setTools(List.copyOf(config.getTools()));
    dto.setSkills(List.copyOf(config.getSkills()));
    dto.setSubagents(List.copyOf(config.getSubagents()));
    // canonical config JSON 只含 tools/skills/subagents/inheritParentEnvironment，不含任何凭据。
    dto.setConfigurationJson(agentConfigCodec.encode(config));
    return dto;
  }

  private AgentDefinitionConfigDTO decodeSubagentConfig(AgentDefinition definition) {
    try {
      return agentConfigCodec.decode(definition.configJson());
    } catch (IllegalStateException error) {
      throw new IllegalStateException(
          "subagent has an invalid configuration: " + definition.name(), error);
    }
  }

  private static HarnessModelRequestDebugDTO.CacheControlDTO cacheControl(
      ProviderCacheControl cacheControl) {
    HarnessModelRequestDebugDTO.CacheControlDTO dto =
        new HarnessModelRequestDebugDTO.CacheControlDTO();
    dto.setRetention(cacheControl.retention().name());
    dto.setKey(cacheControl.key());
    return dto;
  }

  private static HarnessModelSelectionDTO modelSelection(ModelSelection selection) {
    HarnessModelSelectionDTO dto = new HarnessModelSelectionDTO();
    dto.setModelName(selection.modelName());
    dto.setVariant(selection.variant());
    return dto;
  }

  /** 没有 Builtin 当前 generation 时为 null（空闲 Thread），不生成占位请求。 */
  private HarnessModelRequestDebugDTO.FrozenInvocationDTO frozenInvocation(UUID threadId) {
    BuiltinRequestView view = builtinRequests.findCurrent(threadId).orElse(null);
    if (view == null) {
      return null;
    }
    HarnessModelRequestDebugDTO.FrozenInvocationDTO dto =
        new HarnessModelRequestDebugDTO.FrozenInvocationDTO();
    dto.setKind(FROZEN_INVOCATION_KIND);
    dto.setGenerationId(view.generationId().toString());
    dto.setAttempt(view.attempt());
    dto.setRequestJson(BUILTIN_REQUESTS.encode(view.requestSpec()));
    return dto;
  }
}
