package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** 签发短期安装 code 的请求；只携带当前乐观锁版本。 */
@Data
public class EnvironmentInstallCodeRequestDTO {
  private String expectedVersion;
}
