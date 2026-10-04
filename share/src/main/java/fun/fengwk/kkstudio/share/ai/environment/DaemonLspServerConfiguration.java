package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.util.List;

/** 已安装的语言服务器命令、文件扩展名与项目根标记，不负责安装或探测程序。 */
@Data
public class DaemonLspServerConfiguration {
  private List<String> command;
  private List<String> extensions;
  private List<String> rootMarkers;
  private List<String> firstMatchMarkers;
}
