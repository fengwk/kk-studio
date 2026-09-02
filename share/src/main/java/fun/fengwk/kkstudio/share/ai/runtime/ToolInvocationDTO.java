package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * ToolInvocation 查询投影（tool_invocation 表）；实体 id 均为 canonical UUID string。
 *
 * <p>工具身份字段由 durable ToolCall 直接派生：{@code toolCallId} / {@code toolName} / {@code argumentsJson}
 * 恒有值。binding 派生字段仅在 binding 非 null 时有值：{@code toolId} / {@code toolVersion} / {@code environment}
 * （binding 为 null（unknown tool 槽位）时显式序列化 null），而 {@code rendererKey} 在 binding 非 null 时来自
 * binding，否则固定回退为 {@code tool}。{@code approvalJson} / {@code resultJson} / {@code errorJson} 为
 * canonical runtime codec JSON，仅对应阶段非 null；{@code environment} 为 nullable 完整 Environment
 * binding（{@code {environmentId, workspacePath}}）。
 */
@Data
public class ToolInvocationDTO {
  /** Invocation 主键：canonical UUID string。 */
  private String id;

  /** 所属 ModelInvocation 主键：canonical UUID string。 */
  private String modelInvocationId;

  /** 携带 tool calls 的 ASSISTANT Message Entry 主键：canonical UUID string。 */
  private String assistantEntryId;

  /** 在同一 Model 结果 tool calls 中的下标（非负整数，0..N-1 连续前缀）。 */
  private Integer callIndex;

  /**
   * 状态，取 {@code ToolInvocationStatus} 枚举名：WAITING_APPROVAL / READY / DISPATCHING / RUNNING /
   * SUCCEEDED / FAILED / CANCELLED / UNKNOWN。
   */
  private String status;

  /** 执行侧已确认的尝试次数（非负整数）；BUSY/OVERLOADED 等未确认执行不计数。 */
  private Integer attempt;

  /** Provider 侧 tool call id（冻结的调用标识），恒来自 durable ToolCall。 */
  private String toolCallId;

  /** 冻结 ToolCall 的工具名（canonical tool name），恒有值（不依赖 binding）。 */
  private String toolName;

  /** 绑定工具版本字符串；binding 为 null（unknown tool 槽位）时显式序列化 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String toolVersion;

  /** 冻结的编译期前端 Tool renderer contribution id；binding 为 null 时固定回退为 {@code tool}。 */
  private String rendererKey;

  /** 稳定 AgentToolId；binding 为 null（unknown tool 槽位）时显式序列化 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String toolId;

  /**
   * 完整 Environment binding：绑定环境的工具为冻结的 {@code {environmentId, workspacePath}}，其它为
   * null（{@code @JsonInclude(ALWAYS)} 保证 null 显式序列化）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private EnvironmentBindingDTO environment;

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

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
