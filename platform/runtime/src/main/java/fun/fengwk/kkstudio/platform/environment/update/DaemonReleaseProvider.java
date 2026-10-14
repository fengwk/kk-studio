package fun.fengwk.kkstudio.platform.environment.update;

import java.util.Optional;

/** 官方 Daemon 发布目标解析窄端口：只解析当前平台版本对应的固定官方发布，不存在任意版本/任意 URL 解析。 */
public interface DaemonReleaseProvider {

  /** 解析当前平台版本对应的官方发布目标；发布不可用（缺失、校验文件缺失或非法）时返回空。 */
  Optional<DaemonReleaseTarget> resolveTarget();
}
