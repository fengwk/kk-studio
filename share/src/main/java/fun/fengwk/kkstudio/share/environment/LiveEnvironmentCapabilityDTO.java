package fun.fengwk.kkstudio.share.environment;

import lombok.Data;

/** live Environment 发布的原子 capability 身份。 */
@Data
public class LiveEnvironmentCapabilityDTO {
  /** capability 的稳定 canonical ID。 */
  private String id;
}
