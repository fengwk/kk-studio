package fun.fengwk.kkstudio.platform.harness.thread.command;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;

import java.util.List;
import java.util.Objects;

/**
 * {@link DatabaseTurnResolver} 的只读 live 规划结果。
 *
 * <p>正式 Turn 与 Debug 预览消费的是同一次规划：{@link Planned} 携带的 {@link ModelRequestSpec} 就是运行时冻结进
 * ModelInvocation 的那个 spec，候选 Tool / Skill 事实只是同一次解析的结构化投影，因此预览不可能与真实请求漂移。规划本身不写库、不做 CAS、不发
 * Package、不触发 sync。
 *
 * <p>{@link Rejected} 只承载稳定 error code 与供运行期 {@code AssistantError} 使用的自由文本 detail；对外 Debug 投影只使用
 * code， 绝不回显 detail。基础设施与编程异常不经由本类型表达，它们照常向上传播。
 */
public sealed interface LiveTurnPlan permits LiveTurnPlan.Planned, LiveTurnPlan.Rejected {

  /** 规划成功：冻结请求加上仅供结构化投影的候选事实。 */
  record Planned(
      ModelRequestSpec spec,
      int contextWindow,
      List<CandidateTool> candidateTools,
      List<PlannedSkill> skills)
      implements LiveTurnPlan {

    public Planned {
      spec = Objects.requireNonNull(spec, "spec");
      if (contextWindow <= 0) {
        throw new IllegalArgumentException("contextWindow must be positive");
      }
      candidateTools = List.copyOf(Objects.requireNonNull(candidateTools, "candidateTools"));
      skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
      requireSentCandidatesFirst(candidateTools);
    }

    /** 发送候选必须整体先于被过滤候选，且与 spec 中冻结的 toolBindings 一一对应。 */
    private static void requireSentCandidatesFirst(List<CandidateTool> candidateTools) {
      boolean filteredSeen = false;
      for (CandidateTool tool : candidateTools) {
        if (tool.state() == ToolState.FILTERED) {
          filteredSeen = true;
        } else if (filteredSeen) {
          throw new IllegalArgumentException(
              "SENT candidate " + tool.name() + " must precede FILTERED candidates");
        }
      }
    }
  }

  /**
   * 规划被确定性拒绝。
   *
   * @param errorCode 稳定错误码，与 {@link DatabaseTurnResolver#REJECTION_CODE} 一致
   * @param detail 自由文本详情：只用于运行期 {@code AssistantError}，绝不进入对外投影
   */
  record Rejected(String errorCode, String detail) implements LiveTurnPlan {

    public Rejected {
      errorCode = Objects.requireNonNull(errorCode, "errorCode");
      detail = Objects.requireNonNull(detail, "detail");
    }
  }

  /** 单个候选 Tool 的发送状态。 */
  enum ToolState {
    /** 进入最终模型工具面。 */
    SENT,

    /** 只作为候选事实保留，不进入最终模型工具面。 */
    FILTERED
  }

  /** 候选 Tool 被过滤的稳定原因；当前唯一取值是「branch 未选择 Environment」。 */
  enum FilterReason {
    /** REQUIRED 工具在 branch 未选择 Environment 时被过滤。 */
    ENVIRONMENT_NOT_SELECTED
  }

  /**
   * 一个候选 Tool 的模型可见事实。
   *
   * @param name 模型可见工具名
   * @param description 工具描述
   * @param inputSchemaJson 最终 Provider definition 的 input schema 原文
   * @param environmentSupport 与 Environment 的关系
   * @param requiredEnvironmentId 贡献固定要求的目标 Environment；不限定时为 null
   * @param provenance Contributor 归属身份（{@code contributorId:localName}）
   * @param state 是否进入最终模型工具面
   * @param filterReason 被过滤的稳定原因；{@code SENT} 时为 null
   */
  record CandidateTool(
      String name,
      String description,
      String inputSchemaJson,
      EnvironmentSupport environmentSupport,
      EnvironmentId requiredEnvironmentId,
      String provenance,
      ToolState state,
      FilterReason filterReason) {

    public CandidateTool {
      name = Objects.requireNonNull(name, "name");
      description = Objects.requireNonNull(description, "description");
      inputSchemaJson = Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
      environmentSupport = Objects.requireNonNull(environmentSupport, "environmentSupport");
      provenance = Objects.requireNonNull(provenance, "provenance");
      state = Objects.requireNonNull(state, "state");
      if (state == ToolState.SENT && filterReason != null) {
        throw new IllegalArgumentException("SENT candidate must not carry a filter reason");
      }
      if (state == ToolState.FILTERED && filterReason == null) {
        throw new IllegalArgumentException("FILTERED candidate requires a stable filter reason");
      }
    }
  }

  /**
   * 一个 Agent 配置 Skill 的交付事实。
   *
   * @param packageName Skill 所属 Package 名
   * @param name Package 内的 Skill 名
   * @param description manifest 中的 Skill 描述
   * @param path 实际 Prompt 使用的稳定 path
   * @param delivery 交付方式；LOCAL 只在精确安装 currentCommit 时成立
   * @param currentCommit Package 当前发布 commit
   * @param observedHeadCommit Package 最近检查到的 branch HEAD；从未成功检查时为 null
   * @param installedCommit 当前 Environment 已安装的 commit；无 Environment 或未安装时为 null
   */
  record PlannedSkill(
      String packageName,
      String name,
      String description,
      String path,
      SkillPromptResolution.Delivery delivery,
      String currentCommit,
      String observedHeadCommit,
      String installedCommit) {

    public PlannedSkill {
      packageName = Objects.requireNonNull(packageName, "packageName");
      name = Objects.requireNonNull(name, "name");
      description = Objects.requireNonNull(description, "description");
      path = Objects.requireNonNull(path, "path");
      delivery = Objects.requireNonNull(delivery, "delivery");
      currentCommit = Objects.requireNonNull(currentCommit, "currentCommit");
      if (delivery == SkillPromptResolution.Delivery.LOCAL
          && !currentCommit.equals(installedCommit)) {
        throw new IllegalArgumentException(
            "LOCAL skill delivery requires the exact installed commit");
      }
    }

    /** 仅供 Prompt 渲染的三元组：与交付事实同源，因此 Prompt XML 与结构化投影不可能漂移。 */
    public SkillPromptEntry promptEntry() {
      return new SkillPromptEntry(name, description, path);
    }
  }
}
