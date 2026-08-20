package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/** 命令接受响应中的 Session 身份摘要。 */
@Data
public class HarnessSessionDTO {

  /** Session canonical UUID string。 */
  private String sessionId;

  /** Session 创建时间。 */
  private Instant createdAt;
}
