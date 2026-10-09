package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/** 人工终端启动配置：显式可执行程序、按原样组成的 argv 与绝对启动目录；省略即使用宿主默认值，不会丢弃空参数。 */
@Data
public class DaemonTerminalConfiguration {
  private String executable;
  @ToString.Exclude private List<String> args;
  private String workdir;
}
