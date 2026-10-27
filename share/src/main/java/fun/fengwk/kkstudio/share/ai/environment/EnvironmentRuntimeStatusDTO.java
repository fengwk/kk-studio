package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** Environment 当前运行快照的 HTTP 投影；{@code revision} 为控制代际内的规范正十进制字符串。 */
@Data
public class EnvironmentRuntimeStatusDTO {
  private String revision;
  private EnvironmentRuntimeDTO status;
}
