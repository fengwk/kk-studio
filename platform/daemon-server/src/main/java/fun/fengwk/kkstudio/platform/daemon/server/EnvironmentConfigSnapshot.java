package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.DaemonConfiguration;

import java.util.Objects;

/**
 * 一张 Environment 卡片的权威配置快照。
 *
 * <p>{@code version} 是卡片 CAS 版本，也是完整配置快照版本；{@code config} 直接使用控制协议生成的不可变 {@link
 * DaemonConfiguration}，避免 daemon-server 依赖 runtime DTO，并让下发路径复用同一份类型化声明。
 */
public record EnvironmentConfigSnapshot(long version, DaemonConfiguration config) {

  public EnvironmentConfigSnapshot {
    config = Objects.requireNonNull(config, "config");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be non-negative");
    }
  }
}
