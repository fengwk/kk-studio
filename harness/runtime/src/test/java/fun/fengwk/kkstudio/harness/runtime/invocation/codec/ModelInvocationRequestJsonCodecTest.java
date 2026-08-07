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

/** 完整 frozen 的 Model 请求 wire，包含 route 拥有的 Tool 与 Skill binding。 */
class ModelInvocationRequestJsonCodecTest {

  private final ModelInvocationRequestJsonCodec codec = new ModelInvocationRequestJsonCodec();
  private final ProviderRequestJsonCodec providerCodec = new ProviderRequestJsonCodec();
  private final ToolBindingJsonCodec bindingCodec = new ToolBindingJsonCodec();

  /** 精确组合既证明顶层字段顺序，又让嵌套 codec 各自保持单一权威。 */
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

  /** 仅 platform 请求的 null route 保持显式，不获得 fallback 身份。 */
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

  /** 严格 parser 设置在嵌套 decode 之前拒绝畸形的顶层文档。 */
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

  /** 精确字段与标量/容器类型以 deterministic 方式拒绝被破坏的 JSONB 行。 */
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

  /** 聚合构造重新校验 provider/binding 基数以及单一 route 所有权。 */
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
