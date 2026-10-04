package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.util.Map;

/** 按声明顺序选择服务器；启用 LSP 时至少声明一个服务器。 */
@Data
public class DaemonLspConfiguration {
  private Map<String, DaemonLspServerConfiguration> servers;
}
