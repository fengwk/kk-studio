package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * Daemon READY 上报的版本化宿主 metadata。
 *
 * <p>这是 READY 的唯一 wire 形状：版本号加一份 {@link DaemonEnvironmentInfo}。READY 只描述宿主本身，不携带任何产品目录事实。
 */
public record DaemonCapabilities(int version, DaemonEnvironmentInfo environment) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 1;

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    environment = Objects.requireNonNull(environment, "environment");
  }
}
