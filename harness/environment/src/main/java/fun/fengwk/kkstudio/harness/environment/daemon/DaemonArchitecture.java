package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * Daemon READY 上报的稳定、canonical CPU 架构。
 *
 * <p>wire 值固定为 {@code amd64}/{@code arm64}：Node 侧由 {@code process.arch} 映射而来（{@code x64 → amd64}），
 * 用于在官方发布矩阵中挑选与本机一致的发行归档。它不是任意自由文本，未知取值一律拒绝。
 */
public enum DaemonArchitecture {
  AMD64("amd64"),
  ARM64("arm64");

  private final String wireValue;

  DaemonArchitecture(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static DaemonArchitecture fromWireValue(String value) {
    for (DaemonArchitecture architecture : values()) {
      if (architecture.wireValue.equals(value)) {
        return architecture;
      }
    }
    throw new IllegalArgumentException("unsupported daemon architecture: " + value);
  }
}
