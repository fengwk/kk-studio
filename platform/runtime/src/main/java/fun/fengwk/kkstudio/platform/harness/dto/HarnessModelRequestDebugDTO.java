package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan.FilterReason;

import java.time.Instant;
import java.util.List;

/**
 * Thread Debug 的结构化模型请求投影。
 *
 * <p>顶层是现算的下一次请求预览（{@code kind = NEXT_REQUEST_PREVIEW}）：它与正式 Turn 复用同一套
 * Agent/Environment/Tool/Skill 解析纯函数，不检查 branch HEAD、不发布 Package、不触发 Daemon sync，也绝不冒充历史请求。顶层
 * {@code model}/{@code environmentName} 来自本次请求的草稿选择，Agent 引用与 Goal 仍取自 branch settings；规划结果只按草稿选择
 * 现算，不写回任何持久事实。活动 ModelInvocation 的冻结 canonical ProviderRequest 只出现在可空的 {@link FrozenInvocationDTO}
 * 中，且始终按原始 snapshot 与原路径物化，与草稿选择无关；已结束 Turn 的 Invocation 行已被删除，因此那里 不会有残留。
 *
 * <p>完整 schema 与配置以 JSON 节点展示，Provider 请求体保持原文字符串；credential、Authorization header、对象存储内部地址与 Base64
 * 正文一律不进入本 DTO。
 */
@Data
public class HarnessModelRequestDebugDTO {

  /** 视图判别符：恒为 {@code NEXT_REQUEST_PREVIEW}，表示这是现算预览而不是历史实际请求。 */
  private Kind kind;

  /** 预览生成时间。 */
  private Instant generatedAt;

  /** 本次预览使用的 branch model 选择。 */
  private HarnessModelSelectionDTO model;

  /** branch 当前选择的 Environment 名；未选择时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String environmentName;

  /** 本次请求唯一的 systemInstruction 全文。 */
  private String systemInstruction;

  /** 全部候选 Tool：最终发出的定义在前，被过滤的候选在后。 */
  private List<ToolDTO> tools;

  /** 本次请求的 Skill 交付事实，按 Agent 配置顺序。 */
  private List<SkillDTO> skills;

  /** 本次请求的 subagent allowlist。 */
  private List<SubagentDTO> subagents;

  /** Provider cache control 事实。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable CacheControlDTO cacheControl;

  /** planning 失败的稳定错误码；成功时为 null（required-nullable）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String planningError;

  /** 活动 ModelInvocation 的冻结 canonical ProviderRequest；没有活动 Invocation 时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable FrozenInvocationDTO frozenInvocation;

  /** 候选 Tool 的发送状态与最终 definition 事实。 */
  @Data
  public static class ToolDTO {

    /** 模型可见工具名。 */
    private String name;

    /** 工具描述。 */
    private String description;

    /** 工具最终 Provider definition 的 input schema。 */
    private JsonNode inputSchema;

    /** Tool 与 Environment 的关系，与 Tool catalog 共用同一个枚举。 */
    private EnvironmentSupport environmentSupport;

    /** Contributor 归属身份（{@code contributorId:localName}）。 */
    private String provenance;

    /** 是否进入最终模型工具面：{@code SENT} 或 {@code FILTERED}。 */
    private LiveTurnPlan.ToolState state;

    /** 被过滤的稳定原因（当前唯一值 {@code ENVIRONMENT_NOT_SELECTED}）；{@code SENT} 时为 null（required-nullable）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Nullable
    private FilterReason filterReason;
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

    /** 实际 Prompt 使用的稳定 path（本地绝对路径或 {@code vfs:/skills/...}）。 */
    private String path;

    /** 交付方式：{@code LOCAL}（Daemon 已安装该工件摘要）或 {@code PLATFORM}（读取 Platform cache）。 */
    private SkillPromptResolution.Delivery delivery;

    /** Package 当前发布版本或 Git commit SHA。 */
    private String packageVersion;

    /** Package 最近检查到的 branch HEAD；从未成功检查时为 null（required-nullable）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private @Nullable String headCommit;

    /** Package 当前工件包的 SHA-256。 */
    private String artifactSha256;

    /** 当前 Environment 已安装的工件包 SHA-256；无 Environment 或未安装该 Package 时为 null（required-nullable）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private @Nullable String installedSha256;

    /** 实际进入 systemInstruction 的 Skill XML 片段；该 Skill 未进入 Prompt 时为 null（required-nullable）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private @Nullable String promptXml;
  }

  /**
   * subagent allowlist 元素：binding 事实（name/description）与目标 Agent 当前配置声明（tools/skills/subagents）。
   *
   * <p>配置来自目标 Agent definition 的 canonical config，只陈述该 Agent 自己声明的能力，不递归展开其 {@code subagents}
   * 指向的下一层 Agent，也不承载任何 credential。它描述的是配置声明，而不是尚未发生的一次子调用请求体。
   */
  @Data
  public static class SubagentDTO {

    /** 可被 {@code task} 委派的 Agent 名。 */
    private String name;

    /** 被委派 Agent 的展示描述。 */
    private String description;

    /** 目标 Agent 配置声明的模型可见 tool name，按声明顺序。 */
    private List<String> tools;

    /** 目标 Agent 配置声明的 Skill 引用，按声明顺序；只含 {@code (packageName, name)} 身份。 */
    private List<SkillRefDTO> skills;

    /** 目标 Agent 配置声明的 subagent 短名，按声明顺序；不递归展开。 */
    private List<String> subagents;

    /** 目标 Agent 配置声明的 canonical config JSON（AgentConfigCodec 编码），不含任何凭据。 */
    private JsonNode configuration;
  }

  /** Provider cache control 事实，与冻结的 {@code ProviderCacheControl} 一一对应。 */
  @Data
  public static class CacheControlDTO {

    /** 留存档位，取 {@code NONE / SHORT / LONG}。 */
    private PromptCacheRetention retention;

    /** 稳定缓存前缀标识（session UUID 文本）；{@code retention = NONE} 时为 null（required-nullable）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private @Nullable String key;
  }

  /** 活动 Builtin generation 的冻结请求。 */
  @Data
  public static class FrozenInvocationDTO {

    /** 视图判别符：恒为 {@code FROZEN_INVOCATION}。 */
    private FrozenKind kind;

    /** Builtin generation 的稳定身份（canonical UUID string）。 */
    private String generationId;

    /** 冻结请求所属 generation 的 attempt。 */
    private Integer attempt;

    /** 由冻结 ModelRequestSpec 与 request head 物化的 canonical ProviderRequest JSON。 */
    private String requestJson;

    /** 冻结请求视图判别符。 */
    public enum FrozenKind {
      FROZEN_INVOCATION
    }
  }

  /** 顶层调试视图判别符。 */
  public enum Kind {
    NEXT_REQUEST_PREVIEW
  }
}
