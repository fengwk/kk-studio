package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 一张 Environment 卡片持久化并由 Daemon 热应用的完整配置；所有外部输入通过 {@link DaemonConfigCodec} 校验。 */
@Data
public class DaemonConfigDTO {
  private String studioUrl;
  private @Nullable String bashExecutable;
  private @Nullable DaemonLspConfiguration lsp;
  private @Nullable String proxyUrl;
}
