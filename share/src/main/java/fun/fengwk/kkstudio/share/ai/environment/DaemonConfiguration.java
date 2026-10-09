package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** 安装配置与 Daemon 文件共用的配置模型；所有外部输入通过 {@link DaemonConfigurationCodec} 校验。 */
@Data
public class DaemonConfiguration {
  private String studioUrl;
  private String note;
  private String bashExecutable;
  private DaemonTerminalConfiguration terminal;
  private DaemonLspConfiguration lsp;
}
