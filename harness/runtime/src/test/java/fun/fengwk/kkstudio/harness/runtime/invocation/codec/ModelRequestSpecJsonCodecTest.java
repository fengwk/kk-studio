package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.environmentModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.hostModelRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

import java.util.List;

/** 紧凑 ModelRequestSpec 的严格 wire：不含 history messages / environment / yolo / contextWindow。 */
class ModelRequestSpecJsonCodecTest {

  private final ModelRequestSpecJsonCodec codec = new ModelRequestSpecJsonCodec();
  private final ModelDescriptorJsonCodec modelCodec = new ModelDescriptorJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  @Test
  void roundTripsEnvironmentRequestWithCanonicalJson() {
    ModelRequestSpec requestSpec = environmentModelRequest();
    String expected =
        "{\"providerType\":\"OPENAI\",\"model\":"
            + modelCodec.encodeDescriptor(requestSpec.model())
            + ",\"variant\":"
            + modelCodec.encodeVariant(requestSpec.variant())
            + ",\"preambleMessages\":[],\"toolBindings\":["
            + bindingCodec.encode(requestSpec.toolBindings().getFirst())
            + "],\"skillBindings\":[{\"name\":\"review\",\"description\":\"Review code\","
            + "\"sourceEnvironment\":{\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"workspacePath\":\".\"}}],\"subagentBindings\":[],\"cacheControl\":"
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
            base.model(),
            base.variant(),
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
  void encodedSpecDoesNotContainHistoryOrYoloOrContextWindow() {
    ObjectNode encoded = codec.encodeNode(environmentModelRequest());
    assertFalse(encoded.has("environment"));
    assertFalse(encoded.has("providerRequest"));
    assertFalse(encoded.has("yoloEnabled"));
    assertFalse(encoded.has("contextWindow"));
    assertFalse(encoded.has("messages"));
    assertFalse(encoded.has("compaction"));
  }

  @Test
  void rejectsCompactionMetadataField() {
    // Compaction metadata belongs exclusively to TURN_START; spec JSON 中出现该字段即拒绝。
    ObjectNode withCompaction = encodedNode();
    withCompaction.putNull("compaction");
    assertInvalid(withCompaction);
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
