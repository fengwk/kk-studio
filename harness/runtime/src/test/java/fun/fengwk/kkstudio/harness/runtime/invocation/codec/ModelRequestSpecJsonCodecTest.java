package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.environmentModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.hostModelRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 紧凑 ModelRequestSpec 的严格 wire 与 round-trip 契约。 */
class ModelRequestSpecJsonCodecTest {

  private final ModelRequestSpecJsonCodec codec = new ModelRequestSpecJsonCodec();
  private final ModelDescriptorJsonCodec modelCodec = new ModelDescriptorJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  @Test
  void roundTripsEnvironmentRequestWithCanonicalJson() {
    ModelRequestSpec requestSpec = environmentModelRequest();
    String expected =
        "{\"providerType\":\"OPENAI\",\"providerConnectionGenerationId\":\""
            + requestSpec.providerConnectionGenerationId()
            + "\",\"model\":"
            + modelCodec.encodeDescriptor(requestSpec.model())
            + ",\"variant\":"
            + modelCodec.encodeVariant(requestSpec.variant())
            + ",\"outputTokens\":"
            + requestSpec.outputTokens()
            + ",\"preambleMessages\":[],\"toolBindings\":["
            + bindingCodec.encode(requestSpec.toolBindings().getFirst())
            + "],\"skillBindings\":[{\"name\":\"review\",\"packageName\":\"review-package\","
            + "\"packageVersion\":\"1.0.0\",\"description\":\"Review code\"}],"
            + "\"subagentBindings\":[],\"cacheControl\":"
            + providerCodec.encodeCacheControlNode(requestSpec.cacheControl())
            + "}";

    assertEquals(expected, codec.encode(requestSpec));
    assertEquals(requestSpec, codec.decode(expected));
    assertEquals(requestSpec, codec.decodeNode(codec.encodeNode(requestSpec)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> codec.decode(expected).toolBindings().add(requestSpec.toolBindings().getFirst()));
  }

  @Test
  void roundTripsFrozenSubagentBindings() {
    ModelRequestSpec base = hostModelRequest();
    ModelRequestSpec requestSpec =
        new ModelRequestSpec(
            base.providerType(),
            base.providerConnectionGenerationId(),
            base.model(),
            base.variant(),
            base.outputTokens(),
            "Test system instruction.",
            base.toolBindings(),
            base.skillBindings(),
            List.of(new SubagentBinding("reviewer", "Review changes")),
            base.cacheControl());

    String encoded = codec.encode(requestSpec);
    assertTrue(
        encoded.contains(
            "\"subagentBindings\":[{\"name\":\"reviewer\","
                + "\"description\":\"Review changes\"}]"));
    assertEquals(requestSpec, codec.decode(encoded));
  }

  // 测试意图: agent_definition.description 放开为 text 后，subagent 描述不能再被 512 字符上限拒绝，
  // 且长描述必须无损地穿过冻结 spec 的编解码边界。
  @Test
  void roundTripsSubagentDescriptionBeyondLegacyLengthLimit() {
    ModelRequestSpec base = hostModelRequest();
    String longDescription = "d".repeat(4096);
    ModelRequestSpec requestSpec =
        new ModelRequestSpec(
            base.providerType(),
            base.providerConnectionGenerationId(),
            base.model(),
            base.variant(),
            base.outputTokens(),
            "Test system instruction.",
            base.toolBindings(),
            base.skillBindings(),
            List.of(new SubagentBinding("reviewer", longDescription)),
            base.cacheControl());

    ModelRequestSpec decoded = codec.decode(codec.encode(requestSpec));
    assertEquals(longDescription, decoded.subagentBindings().getFirst().description());
  }

  @Test
  void encodedSpecContainsExactlyTheFrozenInvocationFields() {
    ObjectNode encoded = codec.encodeNode(environmentModelRequest());
    Set<String> fieldNames = new HashSet<>();
    encoded.fieldNames().forEachRemaining(fieldNames::add);
    assertEquals(
        Set.of(
            "providerType",
            "providerConnectionGenerationId",
            "model",
            "variant",
            "outputTokens",
            "preambleMessages",
            "toolBindings",
            "skillBindings",
            "subagentBindings",
            "cacheControl"),
        fieldNames);
  }

  @Test
  void rejectsUnknownMissingAndWrongTypeFacts() {
    ObjectNode extra = encodedNode();
    extra.put("extra", true);
    assertInvalid(extra);

    ObjectNode missing = encodedNode();
    missing.remove("toolBindings");
    assertInvalid(missing);

    ObjectNode providerType = encodedNode();
    providerType.put("providerType", "UNKNOWN");
    assertInvalid(providerType);

    ObjectNode providerConnectionGenerationId = encodedNode();
    providerConnectionGenerationId.put("providerConnectionGenerationId", "not-a-uuid");
    assertInvalid(providerConnectionGenerationId);

    ObjectNode toolsType = encodedNode();
    toolsType.putObject("toolBindings");
    assertInvalid(toolsType);

    // Skill binding 的包身份全部必填，缺失时必须严格拒绝。
    for (String field : List.of("packageName", "packageVersion", "name", "description")) {
      ObjectNode missingSkillField = encodedNode();
      ObjectNode skill = (ObjectNode) missingSkillField.path("skillBindings").get(0);
      skill.remove(field);
      assertInvalid(missingSkillField);
    }
  }

  @Test
  void rejectsNullEncodeAndMalformedDocuments() {
    String json = codec.encode(environmentModelRequest());
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
  }

  private ObjectNode encodedNode() {
    return codec.encodeNode(environmentModelRequest());
  }

  private void assertInvalid(ObjectNode node) {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }
}
