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

  /** 当前 attempt 的非负十进制 sequence；保持 bigint-safe 的 HTTP wire 字符串。 */
  private String sequence;

  private String text;
  private String thinking;
  private String errorCode;
  private String errorMessage;
  private Instant failedAt;
  private Instant retryAt;
}
