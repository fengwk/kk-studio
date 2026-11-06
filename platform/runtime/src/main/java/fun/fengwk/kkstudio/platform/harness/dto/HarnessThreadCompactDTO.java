package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/** 手动压缩请求；实际提交由 ThreadProcessor 的 version fence 决定。 */
@Data
public class HarnessThreadCompactDTO {

  /** exact non-negative version cursor。 */
  private Long expectedVersion;
}
