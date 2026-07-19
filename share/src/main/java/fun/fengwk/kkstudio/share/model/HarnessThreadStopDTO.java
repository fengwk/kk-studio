package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Stop 请求的网络幂等键。 */
@Data
public class HarnessThreadStopDTO {
  private String clientRequestId;
}
