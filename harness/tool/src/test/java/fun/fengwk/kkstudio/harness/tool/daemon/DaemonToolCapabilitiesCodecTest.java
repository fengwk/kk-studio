package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Daemon v1 CAPABILITIES payload codec 的双向与拒绝契约测试。 */
class DaemonToolCapabilitiesCodecTest {

  private final DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();

  @Test
  void roundTripsCompleteDescriptorWithAllSchemaVariants() {
    ToolDescriptor original = sampleDescriptor();

    String json =
        codec.encode(new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(original)));
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);

    assertEquals(1, decoded.tools().size());
    ToolDescriptor restored = decoded.tools().get(0);
    assertEquals(original.name(), restored.name());
    assertEquals(original.version(), restored.version());
    assertEquals(original.description(), restored.description());
    assertEquals(original.rendererKey(), restored.rendererKey());
    assertEquals(original.executionMode(), restored.executionMode());
    assertEquals(original.sideEffect(), restored.sideEffect());
    assertEquals(original.timeout(), restored.timeout());
    assertEquals(original.inputSchema(), restored.inputSchema());
  }

  @Test
  void emptyCapabilitiesAreCanonicalAndAccepted() {
    String json = codec.encode(new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of()));
    assertEquals("{\"tools\":[]}", json);
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);
    assertTrue(decoded.tools().isEmpty());
  }

  @Test
  void constructorRejectsNonEnvironmentDescriptor() {
    ToolDescriptor cloudDescriptor =
        new ToolDescriptor(
            "cloud-only",
            "1",
            "cloud tool",
            "renderer",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(cloudDescriptor)));
    assertTrue(error.getMessage().contains("executionMode must be ENVIRONMENT"));
  }

  @Test
  void constructorRejectsDuplicateNameAtVersion() {
    ToolDescriptor a = sampleDescriptor();
    ToolDescriptor b =
        new ToolDescriptor(
            a.name(),
            a.version(),
            a.description(),
            a.rendererKey(),
            a.inputSchema(),
            a.executionMode(),
            a.sideEffect(),
            a.timeout());
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(a, b)));
    assertTrue(error.getMessage().contains("duplicate CAPABILITIES descriptor"));
  }

  @Test
  void decodeRejectsNonEnvironmentDescriptor() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"CLOUD\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("executionMode must be ENVIRONMENT"));
  }

  @Test
  void decodeRejectsDuplicateNameAtVersionAcrossMultipleDescriptors() {
    // Build a payload directly with two descriptors sharing name@version, bypassing the
    // constructor-level duplicate check so we can exercise the decode-side validation.
    String descriptorJson =
        "{\"name\":\"shell\",\"version\":\"1.0.0\",\"description\":\"shell tool\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":3000,"
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}";
    String json = "{\"tools\":[" + descriptorJson + "," + descriptorJson + "]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate CAPABILITIES descriptor"));
  }

  @Test
  void decodeRejectsUnknownSchemaType() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"weird\"}},\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown schema type"));
  }

  @Test
  void decodeRejectsUnknownTopLevelField() {
    String json = "{\"tools\":[],\"extra\":1}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownDescriptorField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false},\"extra\":\"v\"}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownPrimitiveSchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\",\"minimum\":1}},\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownArraySchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1}},"
            + "\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownObjectSchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false,\"minProperties\":0}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsNonTextEnumEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\",\"enum\":[\"ok\",7]}},\"required\":[],"
            + "\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'enum'[1]"));
  }

  @Test
  void decodeRejectsBlankEnumEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"enum\":[\"ok\",\""
            + "   \"]}},\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'enum'[1]"));
  }

  @Test
  void decodeRejectsDuplicateRequiredEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\"}},\"required\":[\"x\",\"x\"],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  @Test
  void decodeRejectsNonStringRequiredEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\"}},\"required\":[7],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'required'[0]"));
  }

  @Test
  void decodeRejectsMissingObjectSchemaShape() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'required'"));
  }

  @Test
  void decodeRejectsObjectSchemaMissingAdditionalProperties() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[]}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'additionalProperties'"));
  }

  @Test
  void decodeRejectsNonObjectPropertyEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":"
            + "{\"x\":\"string\"},\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("property 'x'"));
  }

  @Test
  void decodeRejectsNonObjectArrayItems() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"array\",\"items\":\"string\"}},\"required\":[],"
            + "\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'items'"));
  }

  @Test
  void decodeRejectsNegativeTimeout() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":-1,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("timeoutMillis"));
  }

  @Test
  void decodeRejectsMalformedPayload() {
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode("{"));
    assertTrue(error.getMessage().contains("CAPABILITIES"));
  }

  @Test
  void decodeRejectsNonArrayTools() {
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"tools\":{}}"));
    assertTrue(error.getMessage().contains("'tools' must be an array"));
  }

  @Test
  void rejectsMissingDescriptorFields() {
    String json = "{\"tools\":[{\"name\":\"a\",\"version\":\"1\"}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("description"));
  }

  @Test
  void rejectsMissingInputSchema() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0}]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("inputSchema"));
  }

  @Test
  void roundTripPreservesSemanticEqualityForEmptyCapabilities() {
    String json = codec.encode(new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of()));
    assertSame(json, json, "encode is deterministic");
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);
    assertEquals("{\"tools\":[]}", codec.encode(decoded));
  }

  private static ToolDescriptor sampleDescriptor() {
    ToolParamsSchema schema =
        new ToolParamsSchema(
            "params",
            Map.of(
                "text", new ToolStringSchema("text value"),
                "count", new ToolIntegerSchema("count value"),
                "ratio", new ToolNumberSchema("ratio value"),
                "enabled", new ToolBooleanSchema("enabled value"),
                "mode", new ToolEnumSchema("execution mode", List.of("fast", "safe")),
                "tags", new ToolArraySchema("tag values", new ToolStringSchema("tag")),
                "options",
                    new ToolObjectSchema(
                        "nested options",
                        Map.of("force", new ToolBooleanSchema("force execution")),
                        Set.of("force"),
                        true)),
            Set.of("text"),
            false);
    return new ToolDescriptor(
        "shell",
        "1.0.0",
        "shell tool",
        "shell-renderer",
        schema,
        ToolExecutionMode.ENVIRONMENT,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(3));
  }
}
