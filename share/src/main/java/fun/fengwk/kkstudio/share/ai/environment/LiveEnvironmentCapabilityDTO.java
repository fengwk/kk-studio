package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** live Environment 发布的原子 capability 身份。 */
@Data
public class LiveEnvironmentCapabilityDTO {
  /** capability 的稳定 canonical ID。 */
  private String id;

  /** capability 版本字符串。 */
  private String version;
}
