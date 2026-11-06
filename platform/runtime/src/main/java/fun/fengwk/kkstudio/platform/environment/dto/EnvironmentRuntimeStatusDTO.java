package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** Environment 当前运行快照的 HTTP 投影；{@code revision} 为控制代际内的规范正十进制字符串。 */
@Data
public class EnvironmentRuntimeStatusDTO {
  private Long revision;
  private EnvironmentRuntimeDTO status;
}
