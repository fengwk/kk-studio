package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Tool approval decision request.
 *
 * <p>{@code decision} is ALLOW or DENY; {@code decisionId} is the stable client-generated
 * idempotency key; {@code actor} is the acting user; {@code reason} is optional.
 */
@Data
public class HarnessToolApprovalDTO {
  private String decision;
  private String decisionId;
  private String actor;
  private String reason;
}
