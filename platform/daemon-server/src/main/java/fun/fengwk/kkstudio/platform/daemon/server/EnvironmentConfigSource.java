package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Optional;

/**
 * 权威 Environment 配置的窄读取端口。
 *
 * <p>实现由 {@code platform/runtime} 提供；daemon-server 只依赖本接口与 {@link EnvironmentConfigSnapshot}，不接触
 * runtime 的 Repository 或 DTO。每次读取都必须返回数据库当前版本与完整配置，不能用历史缓存或默认配置代替。
 */
public interface EnvironmentConfigSource {

  /** 返回该 Environment 当前存在的配置快照；卡片或配置不存在时返回空。 */
  Optional<EnvironmentConfigSnapshot> find(EnvironmentId environmentId);
}
