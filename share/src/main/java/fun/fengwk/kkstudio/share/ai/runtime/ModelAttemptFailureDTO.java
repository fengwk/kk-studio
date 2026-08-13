package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/** Model failed attempt 的历史审计投影。 */
@Data
public class ModelAttemptFailureDTO {
  private String modelInvocationId;
  private String turnStartEntryId;
  private String basisHeadEntryId;
  private Integer attempt;
  private Long sequence;
  private String text;
  private String thinking;
  private String errorCode;
  private String errorMessage;
  private Instant failedAt;
  private Instant retryAt;
}
