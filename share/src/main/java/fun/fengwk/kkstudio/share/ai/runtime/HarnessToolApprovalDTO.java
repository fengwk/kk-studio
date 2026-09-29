package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Tool 审批决策请求。
 *
 * <p>{@code decision} 取 ALLOW 或 DENY；{@code decisionId} 是由客户端生成的稳定幂等键； {@code reason}
 * 为可选字段。操作者身份不在请求体中：由服务端认证上下文决定，客户端无法伪造。
 */
@Data
public class HarnessToolApprovalDTO {
  /** 必填审批决策：仅 ALLOW（放行，invocation 恢复为 READY）或 DENY（拒绝，invocation 终止为 FAILED）。 */
  private String decision;

  /** 必填稳定客户端幂等键（canonical UUID string）；同一决策重放幂等，同 id 不同决策返回 409。 */
  private String decisionId;

  /** 可空自由文本审批理由（≤1024 字符）。 */
  private String reason;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown tool approval field: " + name);
  }
}
