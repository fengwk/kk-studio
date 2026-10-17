package fun.fengwk.kkstudio.platform.environment.update;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.util.Optional;

/** 官方 Daemon 发布目标解析窄端口：只解析当前平台版本与目标宿主平台对应的固定官方发布，不存在任意版本/任意 URL 解析。 */
public interface DaemonReleaseProvider {

  /** 解析当前平台版本与目标宿主平台对应的官方发布目标；发布不可用（缺失、校验文件缺失、非法或平台未覆盖）时返回空。 */
  Optional<DaemonReleaseTarget> resolveTarget(
      DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture);
}
