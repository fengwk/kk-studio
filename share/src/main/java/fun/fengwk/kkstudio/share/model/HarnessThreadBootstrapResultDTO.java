package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread bootstrap 返回值。 */
@Data
public class HarnessThreadBootstrapResultDTO {
  private HarnessSessionDTO session;
  private HarnessThreadDTO thread;
}
