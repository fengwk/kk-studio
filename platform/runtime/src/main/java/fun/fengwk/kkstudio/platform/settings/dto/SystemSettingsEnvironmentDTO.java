package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;

/** environment section：daemon gateway 的资源边界与心跳超时。 */
@Data
public class SystemSettingsEnvironmentDTO {

  private Long heartbeatTimeoutMillis;
}
