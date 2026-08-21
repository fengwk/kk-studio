package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** environment section：daemon gateway 的资源边界与超时。 */
@Data
public class SystemSettingsEnvironmentDTO {

  private Long maxResourceBytes;

  private Long heartbeatTimeoutMillis;

  private Long directoryListTimeoutMillis;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings environment field: " + name);
  }
}
