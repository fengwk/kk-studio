package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** 使用 Environment 版本保存安装设置。 */
@Data
public class EnvironmentInstallConfigUpdateDTO {
  private String expectedVersion;
  private EnvironmentInstallConfigDTO installConfig;
}
