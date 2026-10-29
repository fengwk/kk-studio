package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/** 当前 generation 的 failed attempt 审计投影。 */
@Data
public class HarnessGenerationFailureDTO {

  /** 所属 generation 的稳定身份。 */
  private String generationId;

  /** 所属 Turn 的 TURN_START Entry 身份。 */
  private String turnStartEntryId;

  /** 本次 generation 冻结的 request head Entry 身份。 */
  private String requestHeadEntryId;

  /** 失败 attempt（正整数）。 */
  private Integer attempt;

  /** 该 attempt 输出覆盖到的 canonical 非负十进制 sequence。 */
  private String sequence;

  private String text;
  private String thinking;
  private String errorCode;
  private String errorMessage;
  private Instant failedAt;
  private Instant retryAt;
}
