package fun.fengwk.kkstudio.harness.common.schema;

/** 输入参数 schema 节点。 */
public sealed interface SchemaElement
    permits StringSchema,
        IntegerSchema,
        NumberSchema,
        BooleanSchema,
        EnumSchema,
        ArraySchema,
        ObjectSchema {

  /** 节点描述，可为空。 */
  String description();
}
