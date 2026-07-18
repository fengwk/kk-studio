package fun.fengwk.kkstudio.core.harness.tool.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code tool_invocation} 行映射：一次可恢复的工具调用。 */
@Data
public class ToolInvocationDO {
  /** 主键与执行幂等键（Snowflake）。 */
  private Long id;

  /** 所属 Run。 */
  private Long runId;

  /** 产生该调用的 Assistant Entry。 */
  private Long assistantEntryId;

  /** 同一 Assistant 内的调用序号。 */
  private Integer ordinal;

  /** Provider 侧 tool call id。 */
  private String toolCallId;

  /** 冻结的工具名称。 */
  private String toolName;

  /** 冻结的工具版本。 */
  private String toolVersion;

  /** 目标类型：CONTROL / CLOUD / ENVIRONMENT。 */
  private String targetType;

  /** ENVIRONMENT 目标 id；其他类型为空。 */
  private Long environmentId;

  /** interceptor 处理后的参数 JSON。 */
  private String argumentsJson;

  /** 持久执行状态。 */
  private String status;

  /** 权限策略动作：ALLOW / ASK / DENY。 */
  private String permissionAction;

  /** 用户权限决定：ALLOW / DENY；未决为空。 */
  private String permissionDecision;

  /** 冻结的副作用级别（ToolSideEffect）。 */
  private String sideEffect;

  /** 冻结的执行截止时间。 */
  private LocalDateTime deadlineAt;

  /** 执行 lease owner。 */
  private String leaseOwner;

  /** 执行 lease 截止时间。 */
  private LocalDateTime leaseUntil;

  /** 取消请求时间。 */
  private LocalDateTime cancelRequestedAt;

  /** 确定性结果或执行 ToolResult JSON。 */
  private String resultJson;

  /** 错误摘要。 */
  private String errorMessage;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 开始执行时间。 */
  private LocalDateTime startedAt;

  /** 终态时间。 */
  private LocalDateTime finishedAt;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
