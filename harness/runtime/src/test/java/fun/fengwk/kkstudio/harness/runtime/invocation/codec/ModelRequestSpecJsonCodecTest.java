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
            + "],\"skillBindings\":[{\"name\":\"review\",\"description\":\"Review code\","
            + "\"sourceEnvironmentId\":\"123e4567-e89b-12d3-a456-426614174000\"}],"
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
            base.preambleMessages(),
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
