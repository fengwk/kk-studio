package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Environment 管理操作公开安全响应 DTO。
 *
 * <p>安全边界：物理移除了私有执行参数 {@code arguments}、内部认领代币 {@code leaseToken} 与内部执行节点标识 {@code
 * ownerNodeId}。版本字段均以 canonical 非负十进制字符串表示；摘要字段均为安全结构化 JSON 对象。
 */
@Data
public class EnvironmentOperationDTO {

  /** 操作全局唯一 UUID。 */
  private String id;

  /** 所属 Environment UUID。 */
  private String environmentId;

  /** 目标资源类型：SKILL_SOURCE / MCP_SERVER。 */
  private String resourceType;

  /** 目标资源 UUID。 */
  private String resourceId;

  /** 操作类型：SKILL_REFRESH / SKILL_INSTALL / SKILL_UPDATE / MCP_SERVER_DISCOVER。 */
  private String operationType;

  /** 生命周期状态：PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN / CANCELLED。 */
  private String status;

  /** 冻结的目标资源版本（canonical 非负十进制字符串）。 */
  private String resourceVersion;

  /** 操作参数的安全结构化摘要（仅含来源类型等结构属性，绝不回显 URL、凭证或文件路径）。 */
  private Map<String, Object> parameterSummary;

  /** 截止时间戳（PostgreSQL statement_timestamp 判定）。 */
  private Instant deadlineAt;

  /** 开始执行时间戳；从未认领时为 null。 */
  private Instant startedAt;

  /** 终态完成时间戳；未终结时为 null。 */
  private Instant finishedAt;

  /** 执行成功后的结构化结果摘要（仅含 revision 与 skill/diagnostic 计数）；未成功时为 null。 */
  private Map<String, Object> resultSummary;

  /** 失败或未知状态下的分类错误码；成功或活动状态下为 null。 */
  private String failureCode;

  /** 失败或未知状态下的安全常量描述；成功或活动状态下为 null。 */
  private String failureMessage;

  /** 操作记录创建时间。 */
  private Instant createdAt;

  /** 操作记录最近更新时间。 */
  private Instant updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment operation field: " + fieldName);
  }
}
