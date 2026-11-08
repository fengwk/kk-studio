package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 创建 Environment 卡片：完整 Daemon 配置必填，名称创建后不可变。 */
@Data
public class EnvironmentCreateDTO {

  /** 必填环境名称（<= 64 字符，无首尾空白或斜杠）。 */
  private String name;

  /** 卡片备注；空白归一化为 null。 */
  private @Nullable String note;

  /** 完整 Daemon 配置；studioUrl 必填，其余可空。 */
  private DaemonConfigDTO daemonConfig;
}
