package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.OTHER_ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.environmentModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.platformModelRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

/** Complete frozen Model request wire, including route-owned Tool and Skill bindings. */
class ModelInvocationRequestJsonCodecTest {

  private final ModelInvocationRequestJsonCodec codec = new ModelInvocationRequestJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  /**
   * Exact composition proves top-level order while nested codecs remain their single authorities.
   */
  @Test
  void roundTripsEnvironmentRequestWithCanonicalJson() {
    ModelInvocationRequest request = environmentModelRequest();
    String expected =
        "{\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\",\"providerRequest\":"
            + providerCodec.encode(request.providerRequest())
            + ",\"toolBindings\":["
            + bindingCodec.encode(request.toolBindings().getFirst())
            + "],\"skillBindings\":[{\"name\":\"review\",\"description\":\"Review code\","
            + "\"sourceEnvironmentId\":\""
            + ENVIRONMENT_ID
            + "\"}],\"yoloEnabled\":true}";

    assertEquals(expected, codec.encode(request));
    assertEquals(request, codec.decode(expected));
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> codec.decode(expected).toolBindings().add(request.toolBindings().getFirst()));
  }

  /** Null routes stay explicit for platform-only requests and do not gain fallback identities. */
  @Test
  void roundTripsPlatformRequestWithExplicitNullRoutes() {
    ModelInvocationRequest request = platformModelRequest();
    String encoded = codec.encode(request);

    assertEquals(request, codec.decode(encoded));
    assertNull(codec.decode(encoded).environmentId());
    assertNull(codec.decode(encoded).toolBindings().getFirst().environmentId());
    assertNull(codec.decode(encoded).skillBindings().getFirst().sourceEnvironmentId());
    assertEquals(
        "{\"environmentId\":null,\"providerRequest\":",
        encoded.substring(0, encoded.indexOf('{', 1)));
  }

  /** Strict parser settings reject malformed top-level documents before nested decoding. */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    String json = codec.encode(environmentModelRequest());
    String duplicate =
        json.replace(
            "\"environmentId\":\"" + ENVIRONMENT_ID + "\"",
            "\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"environmentId\":\""
                + OTHER_ENVIRONMENT_ID
                + "\"");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** Exact fields and scalar/container types reject corrupt JSONB rows deterministically. */
  @Test
  void rejectsUnknownMissingAndWrongTypeFacts() {
    ObjectNode extra = encodedNode();
    extra.put("extra", true);
    assertInvalid(extra);

    ObjectNode missing = encodedNode();
    JsonNode skills = missing.remove("skillBindings");
    missing.set("missing", skills);
    assertInvalid(missing);

    ObjectNode environmentType = encodedNode();
    environmentType.put("environmentId", 1);
    assertInvalid(environmentType);

    ObjectNode environmentCanonical = encodedNode();
    environmentCanonical.put("environmentId", ENVIRONMENT_ID.value().toUpperCase());
    assertInvalid(environmentCanonical);

    ObjectNode providerNull = encodedNode();
    providerNull.putNull("providerRequest");
    assertInvalid(providerNull);

    ObjectNode toolsType = encodedNode();
    toolsType.putObject("toolBindings");
    assertInvalid(toolsType);

    ObjectNode skillsType = encodedNode();
    skillsType.putObject("skillBindings");
    assertInvalid(skillsType);

    ObjectNode yoloType = encodedNode();
    yoloType.put("yoloEnabled", "true");
    assertInvalid(yoloType);

    ObjectNode skillNameType = encodedNode();
    firstSkill(skillNameType).put("name", 1);
    assertInvalid(skillNameType);

    ObjectNode skillEnvironmentType = encodedNode();
    firstSkill(skillEnvironmentType).put("sourceEnvironmentId", false);
    assertInvalid(skillEnvironmentType);
  }

  /** Aggregate construction rechecks provider/binding cardinality and one-route ownership. */
  @Test
  void rejectsDomainInvariantMismatchesAfterJsonDecoding() {
    String json = codec.encode(environmentModelRequest());
    String bindingJson = bindingCodec.encode(environmentModelRequest().toolBindings().getFirst());

    assertInvalid(json.replaceFirst(ENVIRONMENT_ID.value(), OTHER_ENVIRONMENT_ID.value()));
    assertInvalid(json.replace("\"toolBindings\":[" + bindingJson + "]", "\"toolBindings\":[]"));
    assertInvalid(
        json.replace(
            "\"toolBindings\":[" + bindingJson + "]",
            "\"toolBindings\":[" + bindingJson + "," + bindingJson + "]"));
    assertInvalid(
        json.replace(
            "\"sourceEnvironmentId\":\"" + ENVIRONMENT_ID + "\"",
            "\"sourceEnvironmentId\":\"" + OTHER_ENVIRONMENT_ID + "\""));
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  private void assertInvalid(JsonNode node) {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  private ObjectNode encodedNode() {
    return codec.encodeNode(environmentModelRequest());
  }

  private static ObjectNode firstSkill(ObjectNode node) {
    return (ObjectNode) ((ArrayNode) node.get("skillBindings")).get(0);
  }
}
