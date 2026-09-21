package fun.fengwk.kkstudio.platform.harness.thread.query;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
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
 * <p>顶层是现算的 {@code NEXT_REQUEST_PREVIEW}：model / environment 来自 branch settings，其余事实来自 {@link
 * DatabaseTurnResolver#planLive} 的只读规划结果——与正式 Turn 完全同源，因此预览不可能与真实请求漂移。预览不检查 branch HEAD、不做 CAS、不发布
 * Package、不触发 Daemon sync，也不产生任何写入。
 *
 * <p>确定性 planning 拒绝只回显稳定 error code，并给出安全的空投影；自由文本拒绝详情绝不外泄。repository / registry / projector
 * 等基础设施或编程异常照常传播，绝不伪装成 planning 失败。
 *
 * <p>只要 snapshot 暴露活动 ModelInvocation，就独立附加可空的 {@code FROZEN_INVOCATION}：它把冻结 {@link
 * ModelRequestSpec} 物化到该 invocation 的 request head 前缀上，再以 canonical {@link
 * ProviderRequestJsonCodec} 编码。request head 是当时 Basis 的精确边界，绝不使用会包含 Tool context 后续 Entry 的当前 head。
 *
 * <p>Platform 不是 Harness 组合根，本服务由 Web 组合根在 {@link HarnessRuntime} 完整装配后显式创建。
 */
public final class ModelRequestDebugService {

  /** 现算预览的视图判别符。 */
  public static final String PREVIEW_KIND = "NEXT_REQUEST_PREVIEW";

  /** 活动 ModelInvocation 冻结请求的视图判别符。 */
  public static final String FROZEN_INVOCATION_KIND = "FROZEN_INVOCATION";

  private final HarnessRuntime runtime;
  private final DatabaseTurnResolver turnResolver;
  private final Clock clock;
  private final ModelRequestMaterializer materializer = new ModelRequestMaterializer();
  private final ProviderRequestJsonCodec requestCodec = new ProviderRequestJsonCodec();

  public ModelRequestDebugService(
      HarnessRuntime runtime, DatabaseTurnResolver turnResolver, Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.turnResolver = Objects.requireNonNull(turnResolver, "turnResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 现算一次结构化 Debug 投影；不存在的 Thread 由 {@link HarnessRuntime} 以 typed 异常拒绝。 */
  public HarnessModelRequestDebugDTO getModelRequestDebug(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    EntryPath path = snapshot.entryPath();
    BranchSettings settings = path.baseSettings();

    HarnessModelRequestDebugDTO dto = new HarnessModelRequestDebugDTO();
    dto.setKind(PREVIEW_KIND);
    dto.setGeneratedAt(clock.instant());
    dto.setModel(modelSelection(settings.model()));
    dto.setEnvironmentName(settings.environmentName());
    LiveTurnPlan plan = turnResolver.planLive(threadId, path);
    if (plan instanceof LiveTurnPlan.Planned planned) {
      project(dto, planned);
    } else {
      projectPlanningError(dto, (LiveTurnPlan.Rejected) plan);
    }
    // 冻结请求与预览相互独立：当前预览被拒绝时活动 Invocation 的冻结事实仍然成立。
    dto.setFrozenInvocation(frozenInvocation(snapshot, path));
    return dto;
  }

  private static void project(HarnessModelRequestDebugDTO dto, LiveTurnPlan.Planned planned) {
    ModelRequestSpec spec = planned.spec();
    dto.setSystemInstruction(spec.systemInstruction());
    dto.setTools(planned.candidateTools().stream().map(ModelRequestDebugService::tool).toList());
    dto.setSkills(planned.skills().stream().map(ModelRequestDebugService::skill).toList());
    dto.setSubagents(
        spec.subagentBindings().stream().map(ModelRequestDebugService::subagent).toList());
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

  private static HarnessModelRequestDebugDTO.SubagentDTO subagent(SubagentBinding subagent) {
    HarnessModelRequestDebugDTO.SubagentDTO dto = new HarnessModelRequestDebugDTO.SubagentDTO();
    dto.setName(subagent.name());
    dto.setDescription(subagent.description());
    return dto;
  }

  private static HarnessModelRequestDebugDTO.CacheControlDTO cacheControl(
      ProviderCacheControl cacheControl) {
    HarnessModelRequestDebugDTO.CacheControlDTO dto =
        new HarnessModelRequestDebugDTO.CacheControlDTO();
    dto.setRetention(cacheControl.retention().name());
    dto.setAffinityKey(cacheControl.affinityKey());
    dto.setBreakpoints(cacheControl.breakpoints().stream().map(Enum::name).sorted().toList());
    return dto;
  }

  private static HarnessModelSelectionDTO modelSelection(ModelSelection selection) {
    HarnessModelSelectionDTO dto = new HarnessModelSelectionDTO();
    dto.setProviderName(selection.providerName());
    dto.setModelName(selection.modelName());
    dto.setVariant(selection.variant());
    return dto;
  }

  /** 没有活动 ModelInvocation 时为 null；否则返回冻结 spec 在 request head 前缀上的 canonical 重建。 */
  private HarnessModelRequestDebugDTO.FrozenInvocationDTO frozenInvocation(
      ThreadSnapshot snapshot, EntryPath path) {
    ModelInvocation model = snapshot.model();
    if (model == null) {
      return null;
    }
    ProviderRequest request =
        materializer.materialize(
            requestHeadPrefix(path, model.requestHeadEntryId()), model.requestSpec());
    HarnessModelRequestDebugDTO.FrozenInvocationDTO dto =
        new HarnessModelRequestDebugDTO.FrozenInvocationDTO();
    dto.setKind(FROZEN_INVOCATION_KIND);
    dto.setRequestJson(requestCodec.encode(request));
    return dto;
  }

  /**
   * root-to-requestHead 前缀：request head 就是本次请求冻结时的 Basis，Tool context 的当前 head 是它的后代，直接用当前 head 会把
   * Assistant 结果投影进请求。request head 不在同一条不可变路径上说明 durable 不变量已破坏，必须 fail closed。
   */
  private static EntryPath requestHeadPrefix(EntryPath path, UUID requestHeadEntryId) {
    List<Entry> entries = path.entries();
    for (int index = 0; index < entries.size(); index++) {
      if (entries.get(index).id().equals(requestHeadEntryId)) {
        return new EntryPath(entries.subList(0, index + 1));
      }
    }
    throw new IllegalStateException(
        "model invocation request head "
            + requestHeadEntryId
            + " is not on the current thread path");
  }
}
