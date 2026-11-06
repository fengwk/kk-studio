package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** live Environment 发布的原子 capability 身份。 */
@Data
public class LiveEnvironmentCapabilityDTO {
  /** capability 的稳定 canonical ID。 */
  private String id;
}
