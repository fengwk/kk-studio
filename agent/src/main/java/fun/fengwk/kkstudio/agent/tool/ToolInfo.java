package fun.fengwk.kkstudio.agent.tool;

import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import lombok.Builder;
import lombok.Data;

/**
 * ToolInfo 表示一个工具的可执行描述信息。
 *
 * 语义说明：
 * - ToolInfo 是工具目录中的稳定描述。
 * - inputSchema 使用自有顶层参数 schema 模型，不直接依赖 LangChain4j。
 * - provider 层再把 inputSchema 转换为底层 SDK 需要的 ToolSpecification。
 *
 * @author fengwk
 */
@Builder
@Data
public class ToolInfo {

    /**
     * 工具名称。
     */
    private final String name;

    /**
     * 工具描述。
     */
    private final String description;

    /**
     * 工具顶层参数输入结构定义。
     */
    private final ToolParamsSchema inputSchema;

}
