package fun.fengwk.kkstudio.harness.tool.daemon;

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

  public String wireValue() {
    return wireValue;
  }

  public static DaemonOperatingSystem fromWireValue(String value) {
    for (DaemonOperatingSystem operatingSystem : values()) {
      if (operatingSystem.wireValue.equals(value)) {
        return operatingSystem;
      }
    }
    throw new IllegalArgumentException("unsupported daemon operating system: " + value);
  }
}
