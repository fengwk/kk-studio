package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/** 命令接受响应中的 Session 身份摘要。 */
@Data
public class HarnessSessionDTO {

  /** Session canonical UUID string。 */
  private String sessionId;

  /** Session 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  private String name;

  /** Session 创建时间。 */
  private Instant createdAt;
}
