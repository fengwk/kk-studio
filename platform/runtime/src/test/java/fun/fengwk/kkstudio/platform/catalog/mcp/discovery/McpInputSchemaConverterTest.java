package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.NumberSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.List;
import java.util.Set;

/**
 * MCP input schema 转换与校验门禁测试。
 *
 * <p>验证标准 JSON Schema（string/integer/number/boolean/array/object/enum）向 Platform canonical schema
 * 的无损映射，以及非法 schema 时的异常防护。
 */
class McpInputSchemaConverterTest {

  @Test
  void convertsCompleteStandardSchema() {
    // 意图：验证包含所有支持类型的复杂 schema 转换
    String json =
        """
        {
          "type": "object",
          "description": "Tool parameters",
          "properties": {
            "query": { "type": "string", "description": "Search query" },
            "limit": { "type": "integer", "description": "Max results" },
            "score": { "type": "number", "description": "Min score" },
            "exact": { "type": "boolean", "description": "Exact match" },
            "tags": { "type": "array", "description": "Tag list", "items": { "type": "string" } },
            "status": { "type": "string", "description": "Item status", "enum": ["active", "archived"] },
            "metadata": {
              "type": "object",
              "description": "Extra meta",
              "properties": {
                "key": { "type": "string" }
              }
            }
          },
          "required": ["query", "limit", "nonExistentField"],
          "additionalProperties": false
        }
        """;

    InputSchema schema = McpInputSchemaConverter.toCanonicalSchema(json, "testTool");
    assertNotNull(schema);
    assertEquals("Tool parameters", schema.description());
    assertFalse(schema.additionalProperties());

    // required 列表应自动过滤掉未在 properties 中声明的项
    assertEquals(Set.of("query", "limit"), schema.required());

    assertInstanceOf(StringSchema.class, schema.properties().get("query"));
    assertEquals("Search query", schema.properties().get("query").description());

    assertInstanceOf(IntegerSchema.class, schema.properties().get("limit"));
    assertInstanceOf(NumberSchema.class, schema.properties().get("score"));
    assertInstanceOf(BooleanSchema.class, schema.properties().get("exact"));

    ArraySchema arraySchema = assertInstanceOf(ArraySchema.class, schema.properties().get("tags"));
    assertInstanceOf(StringSchema.class, arraySchema.items());

    EnumSchema enumSchema = assertInstanceOf(EnumSchema.class, schema.properties().get("status"));
    assertEquals(List.of("active", "archived"), enumSchema.values());

    ObjectSchema objectSchema =
        assertInstanceOf(ObjectSchema.class, schema.properties().get("metadata"));
    assertEquals("Extra meta", objectSchema.description());
    assertInstanceOf(StringSchema.class, objectSchema.properties().get("key"));
  }

  @Test
  void handlesEmptyOrMinimalSchema() {
    // 意图：验证空 schema {} 转换为合法的空 Object schema，默认 additionalProperties=true
    String json = "{}";
    InputSchema schema = McpInputSchemaConverter.toCanonicalSchema(json, "emptyTool");
    assertNotNull(schema);
    assertTrue(schema.properties().isEmpty());
    assertTrue(schema.required().isEmpty());
    assertTrue(schema.additionalProperties());
  }

  @Test
  void mapsUnrecognizedOrCustomTypesToLooseStringSchema() {
    // 意图：对未识别类型（如 custom / anyOf / $ref 等组合结构）降级映射为宽容的 StringSchema，不阻塞校验门禁
    String json =
        """
        {
          "type": "object",
          "properties": {
            "unknownTypeField": { "type": "unknown_custom_type", "description": "Custom field" },
            "noTypeField": { "description": "Untyped field" }
          }
        }
        """;

    InputSchema schema = McpInputSchemaConverter.toCanonicalSchema(json, "customTool");
    assertInstanceOf(StringSchema.class, schema.properties().get("unknownTypeField"));
    assertEquals("Custom field", schema.properties().get("unknownTypeField").description());

    assertInstanceOf(StringSchema.class, schema.properties().get("noTypeField"));
    assertEquals("Untyped field", schema.properties().get("noTypeField").description());
  }

  @Test
  void producesValidCanonicalJsonText() {
    // 意图：验证 toCanonicalJson 输出的文本可被 SchemaJsonCodec 再次无损反序列化
    String json =
        """
        {
          "type": "object",
          "properties": {
            "action": { "type": "string" }
          },
          "required": ["action"]
        }
        """;

    String canonicalJson = McpInputSchemaConverter.toCanonicalJson(json, "tool");
    assertNotNull(canonicalJson);
    assertTrue(canonicalJson.contains("\"action\""));
  }

  @Test
  void rejectsInvalidJsonOrNonObjectRoot() {
    // 意图：验证非法 JSON 格式或顶层为非 object（如 array/string）时抛出 AiValidationException
    assertThrows(
        AiValidationException.class,
        () -> McpInputSchemaConverter.toCanonicalJson("not valid json", "bad"));
    assertThrows(
        AiValidationException.class,
        () -> McpInputSchemaConverter.toCanonicalJson("[\"array\", \"root\"]", "bad"));
    assertThrows(
        AiValidationException.class,
        () -> McpInputSchemaConverter.toCanonicalJson("\"string root\"", "bad"));
    assertThrows(
        AiValidationException.class, () -> McpInputSchemaConverter.toCanonicalJson("123", "bad"));
  }
}
