package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;

/** 保存的安装设置；不包含注册凭据，也不代表配置已经部署。 */
@Data
public class EnvironmentInstallConfigDTO {
  private DaemonOperatingSystem operatingSystem;
  private DaemonConfiguration daemon;
}
