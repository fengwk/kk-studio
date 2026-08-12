package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Tool 审批决策请求。
 *
 * <p>{@code decision} 取 ALLOW 或 DENY；{@code decisionId} 是由客户端生成的稳定幂等键； {@code actor}
 * 是执行审批的用户；{@code reason} 为可选字段。
 */
@Data
public class HarnessToolApprovalDTO {
  /** 必填审批决策：仅 ALLOW（放行，invocation 恢复为 READY）或 DENY（拒绝，invocation 终止为 FAILED）。 */
  private String decision;

  /** 必填稳定客户端幂等键（canonical UUID string）；同一决策重放幂等，同 id 不同决策返回 409。 */
  private String decisionId;

  /** 必填操作者用户标识（canonical name，≤128 字符）。 */
  private String actor;

  /** 可空自由文本审批理由（≤1024 字符）。 */
  private String reason;
}
