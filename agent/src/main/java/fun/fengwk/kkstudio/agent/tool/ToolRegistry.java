package fun.fengwk.kkstudio.agent.tool;

/**
 * ToolRegistry 负责工具的注册与查询。
 *
 * @author fengwk
 */
public interface ToolRegistry {

    /**
     * 注册工具实现。
     *
     * @param name 工具名称
     * @param toolInfo 工具描述信息
     * @param tool 工具实现
     */
    void registerTool(String name, ToolInfo toolInfo, Tool tool);

    /**
     * 按名称解析工具描述信息。
     *
     * @param name 工具名称
     * @return 工具描述信息
     */
    ToolInfo getToolInfo(String name);

    /**
     * 按名称解析工具实现。
     *
     * @param name 工具名称
     * @return 工具实现
     */
    Tool getTool(String name);

}
