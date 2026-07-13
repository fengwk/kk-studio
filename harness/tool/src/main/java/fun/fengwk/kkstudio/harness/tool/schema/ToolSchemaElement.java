package fun.fengwk.kkstudio.harness.tool.schema;

/** Tool 参数 schema 节点。 */
public sealed interface ToolSchemaElement
    permits ToolStringSchema,
        ToolIntegerSchema,
        ToolNumberSchema,
        ToolBooleanSchema,
        ToolEnumSchema,
        ToolArraySchema,
        ToolObjectSchema {

  /** 节点描述，可为空。 */
  String description();
}
