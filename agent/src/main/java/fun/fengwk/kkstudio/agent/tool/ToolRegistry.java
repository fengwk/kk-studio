package fun.fengwk.kkstudio.agent.tool;

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
}
