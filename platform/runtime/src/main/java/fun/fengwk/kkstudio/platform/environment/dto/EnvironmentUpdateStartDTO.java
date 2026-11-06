package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** POST update-daemon 的响应：当前运行快照与命令接收状态。 */
@Data
public class EnvironmentUpdateStartDTO {

  private EnvironmentRuntimeStatusDTO runtimeStatus;
  private EnvironmentUpdateCommandStatus commandStatus;
}
