package fun.fengwk.kkstudio.agent.tool;

import java.util.List;

/**
 * ToolRegistry 负责工具的注册与查询。
 *
 * @author fengwk
 */
public interface ToolRegistry {

  /** 注册工具。 */
  void register(ToolRegistration registration);

  /** 注册工具实现。 */
  default void registerTool(String name, ToolInfo toolInfo, Tool tool) {
    register(new ToolRegistration(name, toolInfo, tool));
  }

  /** 按名称解析工具注册项。 */
  ToolRegistration get(String name);

  /**
   * 按名称解析工具描述信息。
   *
   * @param name 工具名称
   * @return 工具描述信息
   */
  default ToolInfo getToolInfo(String name) {
    ToolRegistration registration = get(name);
    return registration == null ? null : registration.getToolInfo();
  }

  /**
   * 按名称解析工具实现。
   *
   * @param name 工具名称
   * @return 工具实现
   */
  default Tool getTool(String name) {
    ToolRegistration registration = get(name);
    return registration == null ? null : registration.getTool();
  }

  /** 列出当前可用工具名称。 */
  List<String> listToolNames();

  /** 列出当前可用工具注册项。 */
  List<ToolRegistration> listRegistrations();
}
