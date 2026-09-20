package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;

import java.time.Instant;
import java.util.List;

/**
 * Thread Debug 的结构化模型请求投影。
 *
 * <p>顶层是现算的下一次请求预览（{@code kind = NEXT_REQUEST_PREVIEW}）：它与正式 Turn 复用同一套
 * Agent/Environment/Tool/Skill 解析纯函数，不检查 branch HEAD、不发布 Package、不触发 Daemon sync，也绝不冒充历史请求。活动
 * ModelInvocation 的冻结 canonical ProviderRequest 只出现在可空的 {@link FrozenInvocationDTO} 中；已结束 Turn 的
 * Invocation 行已被删除，因此那里不会有残留。
 *
 * <p>完整 schema 与请求 JSON 按字符串原样展示；credential、Authorization header、对象存储内部地址与 Base64 正文一律不进入本 DTO。
 */
@Data
public class HarnessModelRequestDebugDTO {

  /** 视图判别符：恒为 {@code NEXT_REQUEST_PREVIEW}，表示这是现算预览而不是历史实际请求。 */
  private String kind;

  /** 预览生成时间。 */
  private Instant generatedAt;

  /** 本次预览使用的 branch model 选择。 */
  private HarnessModelSelectionDTO model;

  /** branch 当前选择的 Environment 名；未选择时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** 本次请求唯一的 systemInstruction 全文。 */
  private String systemInstruction;

  /** 全部候选 Tool：最终发出的定义在前，被过滤的候选在后。 */
  private List<ToolDTO> tools;

  /** 本次请求的 Skill 交付事实，按 Agent 配置顺序。 */
  private List<SkillDTO> skills;

  /** 本次请求的 subagent allowlist。 */
  private List<SubagentDTO> subagents;

  /** Provider cache control 事实。 */
  private CacheControlDTO cacheControl;

  /** planning 失败的稳定错误码；成功时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String planningError;

  /** 活动 ModelInvocation 的冻结 canonical ProviderRequest；没有活动 Invocation 时为 null。 */
  private FrozenInvocationDTO frozenInvocation;

  /** 候选 Tool 的发送状态与最终 definition 事实。 */
  @Data
  public static class ToolDTO {

    /** 模型可见工具名。 */
    private String name;

    /** 工具描述。 */
    private String description;

    /** 工具最终 Provider definition 的 input schema 原文。 */
    private String inputSchemaJson;

    /** Tool 与 Environment 的关系，与 Tool catalog 共用同一个枚举。 */
    private EnvironmentSupportDTO environmentSupport;

    /** 精确要求的目标 Environment UUID（canonical UUID 文本）；不限定环境时为 null。 */
    private String requiredEnvironmentId;

    /** Contributor 归属身份（{@code contributorId:localName}）。 */
    private String provenance;

    /** 是否进入最终模型工具面：{@code SENT} 或 {@code FILTERED}。 */
    private String state;

    /** 被过滤的稳定原因（当前唯一值 {@code ENVIRONMENT_NOT_SELECTED}）；{@code SENT} 时为 null。 */
    private String filterReason;
  }

  /** 单个 Skill 的交付事实与实际 Prompt 片段。 */
  @Data
  public static class SkillDTO {

    /** Skill 所属 Package 名。 */
    private String packageName;

    /** Package 内的 Skill 名。 */
    private String name;

    /** manifest 中的 Skill 描述。 */
    private String description;

    /** 实际 Prompt 使用的稳定 path（本地绝对路径或 {@code kkstudio:/skills/...}）。 */
    private String path;

    /** 交付方式：{@code LOCAL}（Daemon 已安装该 commit）或 {@code PLATFORM}（读取 Platform cache）。 */
    private String delivery;

    /** Package 当前发布 commit。 */
    private String currentCommit;

    /** Package 最近检查到的 branch HEAD；从未成功检查时为 null。 */
    private String observedHeadCommit;

    /** 当前 Environment 已安装的 commit；无 Environment 或未安装该 Package 时为 null。 */
    private String installedCommit;

    /** 实际进入 systemInstruction 的 Skill XML 片段。 */
    private String promptXml;
  }

  /** subagent allowlist 元素。 */
  @Data
  public static class SubagentDTO {

    /** 可被 {@code task} 委派的 Agent 名。 */
    private String name;

    /** 被委派 Agent 的展示描述。 */
    private String description;
  }

  /** Provider cache control 事实，与冻结的 {@code ProviderCacheControl} 一一对应。 */
  @Data
  public static class CacheControlDTO {

    /** 留存档位，取 {@code NONE / SHORT / LONG}。 */
    private String retention;

    /** 稳定前缀标识；{@code retention = NONE} 时为 null。 */
    private String affinityKey;

    /** 显式 cache 断点，取 {@code SYSTEM} / {@code TOOLS} 的非空子集；{@code retention = NONE} 时为空。 */
    private List<String> breakpoints;
  }

  /** 活动 ModelInvocation 的冻结请求。 */
  @Data
  public static class FrozenInvocationDTO {

    /** 视图判别符：恒为 {@code FROZEN_INVOCATION}。 */
    private String kind;

    /** 由冻结 ModelRequestSpec 与 request head 物化的 canonical ProviderRequest JSON。 */
    private String requestJson;
  }
}
