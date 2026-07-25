package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Daemon v1 CAPABILITIES payload codec 的双向与拒绝契约测试。 */
class DaemonToolCapabilitiesCodecTest {

  private final DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();

  @Test
  void roundTripsCompleteDescriptorWithAllSchemaVariants() {
    ToolDescriptor original = sampleDescriptor();

    String json =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(original), List.of()));
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);

    assertEquals(1, decoded.tools().size());
    ToolDescriptor restored = decoded.tools().get(0);
    assertEquals(original.name(), restored.name());
    assertEquals(original.version(), restored.version());
    assertEquals(original.description(), restored.description());
    assertEquals(original.rendererKey(), restored.rendererKey());
    assertEquals(original.sideEffect(), restored.sideEffect());
    assertEquals(original.timeout(), restored.timeout());
    assertEquals(original.inputSchema(), restored.inputSchema());
  }

  @Test
  void emptyCapabilitiesAreCanonicalAndAccepted() {
    String json =
        codec.encode(new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(), List.of()));
    assertEquals("{\"tools\":[],\"skills\":[]}", json);
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);
    assertTrue(decoded.tools().isEmpty());
  }

  @Test
  void encodeIsDeterministicAcrossInsertionOrders() {
    // Build the same descriptor in three different insertion orders for properties and required.
    Map<String, ToolSchemaElement> hashProps = new HashMap<>();
    hashProps.put("zeta", new ToolStringSchema("z"));
    hashProps.put("alpha", new ToolStringSchema("a"));
    hashProps.put("mu", new ToolStringSchema("m"));
    Set<String> hashRequired = new HashSet<>();
    hashRequired.add("zeta");
    hashRequired.add("alpha");
    hashRequired.add("mu");
    ToolDescriptor hashDescriptor =
        new ToolDescriptor(
            "shell",
            "1",
            "shell",
            "r",
            new ToolParamsSchema("", hashProps, hashRequired, false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    Map<String, ToolSchemaElement> treeProps = new TreeMap<>();
    treeProps.putAll(hashProps);
    Set<String> treeRequired = new TreeSet<>(hashRequired);
    ToolDescriptor treeDescriptor =
        new ToolDescriptor(
            "shell",
            "1",
            "shell",
            "r",
            new ToolParamsSchema("", treeProps, treeRequired, false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    Map<String, ToolSchemaElement> linkedProps = new LinkedHashMap<>();
    linkedProps.put("mu", new ToolStringSchema("m"));
    linkedProps.put("alpha", new ToolStringSchema("a"));
    linkedProps.put("zeta", new ToolStringSchema("z"));
    Set<String> linkedRequired = new LinkedHashSet<>();
    linkedRequired.add("mu");
    linkedRequired.add("alpha");
    linkedRequired.add("zeta");
    ToolDescriptor linkedDescriptor =
        new ToolDescriptor(
            "shell",
            "1",
            "shell",
            "r",
            new ToolParamsSchema("", linkedProps, linkedRequired, false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    String hashJson =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
                List.of(hashDescriptor), List.of()));
    String treeJson =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
                List.of(treeDescriptor), List.of()));
    String linkedJson =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
                List.of(linkedDescriptor), List.of()));

    assertEquals(treeJson, hashJson);
    assertEquals(treeJson, linkedJson);
    // Properties should appear sorted alphabetically in the wire output.
    int alphaIdx = hashJson.indexOf("\"alpha\"");
    int muIdx = hashJson.indexOf("\"mu\"");
    int zetaIdx = hashJson.indexOf("\"zeta\"");
    assertTrue(alphaIdx > 0 && muIdx > alphaIdx && zetaIdx > muIdx);
  }

  @Test
  void encodePreservesToolListOrderAsAdvertised() {
    ToolDescriptor first = tool("alpha", "1");
    ToolDescriptor second = tool("bravo", "1");
    ToolDescriptor third = tool("charlie", "1");
    String json =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
                List.of(first, second, third), List.of()));
    int aIdx = json.indexOf("\"name\":\"alpha\"");
    int bIdx = json.indexOf("\"name\":\"bravo\"");
    int cIdx = json.indexOf("\"name\":\"charlie\"");
    assertTrue(aIdx > 0 && bIdx > aIdx && cIdx > bIdx);
  }

  @Test
  void constructorAcceptsLocationNeutralDescriptor() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "platform-only",
            "1",
            "platform tool",
            "renderer",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    var capabilities =
        new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(descriptor), List.of());
    assertEquals(1, capabilities.tools().size());
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
            a.sideEffect(),
            a.timeout());
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(a, b), List.of()));
    assertTrue(error.getMessage().contains("duplicate CAPABILITIES descriptor"));
  }

  @Test
  void decodeAcceptsLocationNeutralDescriptor() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    var capabilities = codec.decode(json);
    assertEquals(1, capabilities.tools().size());
    assertEquals("a", capabilities.tools().get(0).name());
  }

  @Test
  void decodeRejectsDuplicateNameAtVersionAcrossMultipleDescriptors() {
    String descriptorJson =
        "{\"name\":\"shell\",\"version\":\"1.0.0\",\"description\":\"shell tool\","
            + "\"rendererKey\":\"r\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":3000,"
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}";
    String json = "{\"tools\":[" + descriptorJson + "," + descriptorJson + "],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate CAPABILITIES descriptor"));
  }

  @Test
  void decodeRejectsUnknownSchemaType() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"weird\"}},\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown schema type"));
  }

  @Test
  void decodeRejectsUnknownTopLevelField() {
    String json = "{\"tools\":[],\"extra\":1,\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownDescriptorField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false},\"extra\":\"v\"}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsLegacyExecutionModeField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownPrimitiveSchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\",\"minimum\":1}},\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownArraySchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1}},"
            + "\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsUnknownObjectSchemaField() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false,\"minProperties\":0}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void decodeRejectsNonTextEnumEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\",\"enum\":[\"ok\",7]}},\"required\":[],"
            + "\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'enum'[1]"));
  }

  @Test
  void decodeRejectsBlankEnumEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"enum\":[\"ok\",\""
            + "   \"]}},\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'enum'[1]"));
  }

  @Test
  void decodeRejectsDuplicateRequiredEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\"}},\"required\":[\"x\",\"x\"],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  @Test
  void decodeRejectsNonStringRequiredEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\"}},\"required\":[7],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'required'[0]"));
  }

  @Test
  void decodeRejectsMissingObjectSchemaShape() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'required'"));
  }

  @Test
  void decodeRejectsObjectSchemaMissingAdditionalProperties() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[]}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'additionalProperties'"));
  }

  @Test
  void decodeRejectsNonObjectPropertyEntry() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":"
            + "{\"x\":\"string\"},\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("property 'x'"));
  }

  @Test
  void decodeRejectsNonObjectTopLevelProperties() {
    // Cast to non-object at top-level must surface as DaemonProtocolException, never
    // ClassCastException.
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":"
            + "[\"x\",\"y\"],\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(
        error.getMessage().contains("inputSchema.properties")
            || error.getMessage().contains("'properties'"));
    assertTrue(error.getMessage().contains("JSON object"));
  }

  @Test
  void decodeRejectsNonObjectNestedObjectProperties() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"object\",\"properties\":42,\"required\":[],"
            + "\"additionalProperties\":false}},\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("JSON object"));
  }

  @Test
  void decodeRejectsNonObjectArrayItems() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"array\",\"items\":\"string\"}},\"required\":[],"
            + "\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("items"));
    assertTrue(error.getMessage().contains("JSON object"));
  }

  @Test
  void decodeRejectsUndeclaredRequiredProperty() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"string\"}},\"required\":[\"y\"],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("not declared in 'properties'"));
    assertTrue(error.getMessage().contains("'y'"));
  }

  @Test
  void decodeRejectsDescriptorConstructorValidationFailure() {
    // ToolDescriptor rejects names that contain invalid characters; this surfaces here as
    // DaemonProtocolException with a contextual message.
    String json =
        "{\"tools\":[{\"name\":\"1bad\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("descriptor validation failed"));
    assertTrue(error.getMessage().contains("1bad@1"));
  }

  @Test
  void decodeRejectsNegativeTimeout() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":-1,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}],\"skills\":[]}";
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
        assertThrows(
            DaemonProtocolException.class, () -> codec.decode("{\"tools\":{},\"skills\":[]}"));
    assertTrue(error.getMessage().contains("'tools' must be an array"));
  }

  @Test
  void rejectsMissingDescriptorFields() {
    String json = "{\"tools\":[{\"name\":\"a\",\"version\":\"1\"}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("description"));
  }

  @Test
  void rejectsMissingInputSchema() {
    String json =
        "{\"tools\":[{\"name\":\"a\",\"version\":\"1\",\"description\":\"d\","
            + "\"rendererKey\":\"r\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0}],\"skills\":[]}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("inputSchema"));
  }

  @Test
  void roundTripPreservesEmptyCapabilitiesAsCanonicalText() {
    String first =
        codec.encode(new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(), List.of()));
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(first);
    String second = codec.encode(decoded);
    assertEquals(first, second);
  }

  @Test
  void roundTripsSkillsAlongsideTools() {
    ToolDescriptor tool = tool("shell", "1");
    DaemonSkillDescriptor skill = new DaemonSkillDescriptor("dev", "Developer rules");
    String json =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(tool), List.of(skill)));
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities decoded = codec.decode(json);
    assertEquals(1, decoded.tools().size());
    assertEquals(1, decoded.skills().size());
    assertEquals("dev", decoded.skills().get(0).name());
    assertEquals("Developer rules", decoded.skills().get(0).description());
    assertTrue(
        json.contains("\"skills\":[{\"name\":\"dev\",\"description\":\"Developer rules\"}]"));
  }

  @Test
  void constructorRejectsDuplicateSkillNames() {
    DaemonSkillDescriptor a = new DaemonSkillDescriptor("dev", "one");
    DaemonSkillDescriptor b = new DaemonSkillDescriptor("dev", "two");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(), List.of(a, b)));
    assertTrue(error.getMessage().contains("duplicate CAPABILITIES skill"));
  }

  @Test
  void decodeRejectsMissingSkillsField() {
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"tools\":[]}"));
    assertTrue(error.getMessage().contains("skills"));
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
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(3));
  }

  private static ToolDescriptor tool(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ZERO);
  }
}
