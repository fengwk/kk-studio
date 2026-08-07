package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * ToolInvocation 查询投影（tool_invocation 表）；id 均为 strict positive decimal string。
 *
 * <p>请求字段由 tool binding 与 call 派生；{@code approvalJson} / {@code resultJson} / {@code errorJson} 为
 * canonical runtime codec JSON，仅对应阶段非 null；{@code environmentName} 为 nullable canonical bounded
 * lowercase name route identity。
 */
@Data
public class ToolInvocationDTO {
  /** Invocation 主键：strict positive decimal string。 */
  private String id;

  /** 所属 ModelInvocation 主键：strict positive decimal string。 */
  private String modelInvocationId;

  /** 携带 tool calls 的 ASSISTANT Message Entry 主键：strict positive decimal string。 */
  private String assistantEntryId;

  /** 在同一 Model 结果 tool calls 中的下标（非负整数，0..N-1 连续前缀）。 */
  private Integer ordinal;

  /**
   * 状态，取 {@code ToolInvocationStatus} 枚举名：WAITING_APPROVAL / READY / DISPATCHING / RUNNING /
   * SUCCEEDED / FAILED / CANCELLED / UNKNOWN。
   */
  private String status;

  /** 执行侧已确认的尝试次数（非负整数）；BUSY/OVERLOADED 等未确认执行不计数。 */
  private Integer attempt;

  /** Provider 侧 tool call id（冻结的调用标识）。 */
  private String toolCallId;

  /** 绑定工具名（canonical tool name）。 */
  private String toolName;

  /** 绑定工具版本字符串。 */
  private String toolVersion;

  /** 工具类型，取 {@code ToolType} 枚举名：PLATFORM（平台内置）或 ENVIRONMENT（由 live Environment 提供）。 */
  private String toolType;

  /**
   * 实际 Environment 路由：canonical lowercase 逻辑名称；PLATFORM 工具恒为 null，ENVIRONMENT
   * 工具必填（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** Provider 提供的 JSON 参数原文（tool call arguments）。 */
  private String argumentsJson;

  /** 审批状态：canonical JSON（ToolApprovalJsonCodec）；记录决策后非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String approvalJson;

  /** 执行结果：canonical JSON（ToolResultJsonCodec）；仅成功阶段非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultJson;

  /**
   * 错误详情：canonical JSON（ToolInvocationErrorJsonCodec）；仅记录错误后非 null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String errorJson;

  /** 结果挂载的 Entry 主键：strict positive decimal string；结果应用后非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String resultEntryId;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
