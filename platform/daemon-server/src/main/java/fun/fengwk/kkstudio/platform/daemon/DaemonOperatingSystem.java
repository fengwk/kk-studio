package fun.fengwk.kkstudio.platform.daemon;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Daemon READY environment metadata 中稳定、跨平台的操作系统 family。 */
public enum DaemonOperatingSystem {
  WINDOWS("windows"),
  WSL("wsl"),
  LINUX("linux"),
  MACOS("macos");

  private final String wireValue;

  DaemonOperatingSystem(String wireValue) {
    this.wireValue = wireValue;
  }

  /** HTTP 与配置文件的固定小写 wire 值。 */
  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** 按 wire 值解析；未知取值抛 {@link IllegalArgumentException}。 */
  @JsonCreator
  public static DaemonOperatingSystem fromWireValue(String value) {
    for (DaemonOperatingSystem operatingSystem : values()) {
      if (operatingSystem.wireValue.equals(value)) {
        return operatingSystem;
      }
    }
    throw new IllegalArgumentException("unsupported daemon operating system: " + value);
  }
}
