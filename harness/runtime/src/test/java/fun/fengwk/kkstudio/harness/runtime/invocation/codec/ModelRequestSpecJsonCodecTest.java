package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.environmentModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.platformModelRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.util.List;

/** 紧凑 ModelRequestSpec 的严格 wire：不含 history messages / environment / yolo / contextWindow。 */
class ModelRequestSpecJsonCodecTest {

  private final ModelRequestSpecJsonCodec codec = new ModelRequestSpecJsonCodec();
  private final ModelDescriptorJsonCodec modelCodec = new ModelDescriptorJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  @Test
  void roundTripsEnvironmentRequestWithCanonicalJson() {
    ModelRequestSpec request = environmentModelRequest();
    String expected =
        "{\"providerType\":\"OPENAI\",\"model\":"
            + modelCodec.encodeDescriptor(request.model())
            + ",\"variant\":"
            + modelCodec.encodeVariant(request.variant())
            + ",\"preambleMessages\":[],\"toolBindings\":["
            + bindingCodec.encode(request.toolBindings().getFirst())
            + "],\"skillBindings\":[{\"name\":\"review\",\"description\":\"Review code\","
            + "\"sourceEnvironment\":{\"name\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"workspacePath\":\".\"}}],\"subagentBindings\":[],\"cacheControl\":"
            + providerCodec.encodeCacheControlNode(request.cacheControl())
            + ",\"compaction\":null}";

    assertEquals(expected, codec.encode(request));
    assertEquals(request, codec.decode(expected));
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> codec.decode(expected).toolBindings().add(request.toolBindings().getFirst()));
  }

  @Test
  void roundTripsFrozenSubagentBindings() {
    ModelRequestSpec base = platformModelRequest();
    ModelRequestSpec request =
        new ModelRequestSpec(
            base.providerType(),
            base.model(),
            base.variant(),
            base.preambleMessages(),
            base.toolBindings(),
            base.skillBindings(),
            List.of(new SubagentBinding("reviewer", "Review changes")),
            base.cacheControl(),
            null);

    String encoded = codec.encode(request);
    assertTrue(
        encoded.contains(
            "\"subagentBindings\":[{\"name\":\"reviewer\","
                + "\"description\":\"Review changes\"}]"));
    assertEquals(request, codec.decode(encoded));
  }

  @Test
  void encodedSpecDoesNotContainHistoryOrYoloOrContextWindow() {
    ObjectNode encoded = codec.encodeNode(environmentModelRequest());
    assertFalse(encoded.has("environment"));
    assertFalse(encoded.has("providerRequest"));
    assertFalse(encoded.has("yoloEnabled"));
    assertFalse(encoded.has("contextWindow"));
    assertFalse(encoded.has("messages"));
    assertNull(codec.decodeNode(encoded).compaction());
  }

  @Test
  void roundTripsCompactionRequestWithNumericTokensBefore() {
    ModelRequestSpec base = environmentModelRequest();
    ModelRequestSpec request =
        new ModelRequestSpec(
            base.providerType(),
            base.model(),
            base.variant(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            base.cacheControl(),
            new CompactionRequest(
                CompactionPhase.FULL,
                CompactionTrigger.THRESHOLD,
                500L,
                TestIds.id(2L),
                TestIds.id(4L),
                null));

    String encoded = codec.encode(request);
    assertTrue(
        encoded.contains(
            "\"compaction\":{\"phase\":\"FULL\",\"trigger\":\"THRESHOLD\","
                + "\"tokensBefore\":500,\"firstKeptEntryId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"cutEntryId\":\"00000000-0000-0000-0000-000000000004\","
                + "\"turnPrefixStartEntryId\":null}"),
        encoded);
    assertEquals(request, codec.decode(encoded));

    ObjectNode object = (ObjectNode) codec.encodeNode(request);
    ObjectNode compaction = (ObjectNode) object.get("compaction");
    compaction.put("tokensBefore", "500");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(object));
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
