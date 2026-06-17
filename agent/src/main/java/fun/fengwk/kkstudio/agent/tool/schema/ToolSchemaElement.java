package fun.fengwk.kkstudio.agent.tool.schema;

/**
 * ToolSchemaElement 表示工具输入结构中的一个 schema 节点。
 *
 * @author fengwk
 */
public sealed interface ToolSchemaElement permits ToolStringSchema, ToolIntegerSchema, ToolNumberSchema,
    ToolBooleanSchema, ToolEnumSchema, ToolArraySchema, ToolObjectSchema {

    /**
     * 节点描述。
     */
    String getDescription();

}
