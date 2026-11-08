package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 整体替换 Environment 卡片可编辑属性；name 是不可变身份，不属于更新请求。 */
@Data
public class EnvironmentUpdateDTO {
  private Long expectedVersion;
  private @Nullable String note;
  private DaemonConfigDTO daemonConfig;
}
