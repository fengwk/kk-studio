package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** 保存的安装设置；不包含注册凭据，也不代表配置已经部署。 */
@Data
public class EnvironmentInstallConfigDTO {
  private String operatingSystem;
  private String javaHome;
  private DaemonConfiguration daemon;
}
