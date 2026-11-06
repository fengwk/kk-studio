package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Tool 审批决策请求。
 *
 * <p>{@code decision} 取 ALLOW 或 DENY；{@code decisionId} 是由客户端生成的稳定幂等键； {@code reason}
 * 为可选字段。操作者身份不在请求体中：由服务端认证上下文决定，客户端无法伪造。
 */
@Data
public class HarnessToolApprovalDTO {
  /** 必填审批决策：ALLOW 放行并恢复 invocation，DENY 终止 invocation。 */
  private Decision decision;

  /** 必填稳定客户端幂等键（canonical UUID string）；同一决策重放幂等，同 id 不同决策返回 409。 */
  private String decisionId;

  /** 可空自由文本审批理由（≤1024 字符）。 */
  private @Nullable String reason;

  /** 审批决策 union。 */
  public enum Decision {
    ALLOW,
    DENY
  }
}
