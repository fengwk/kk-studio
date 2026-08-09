package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.OTHER_ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.environmentModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.platformModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.providerRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;

import java.util.List;

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
        "{\"environmentName\":\""
            + ENVIRONMENT_ID
            + "\",\"providerRequest\":"
            + providerCodec.encode(request.providerRequest())
            + ",\"toolBindings\":["
            + bindingCodec.encode(request.toolBindings().getFirst())
            + "],\"skillBindings\":[{\"name\":\"review\",\"description\":\"Review code\","
            + "\"sourceEnvironmentName\":\""
            + ENVIRONMENT_ID
            + "\"}],\"subagentBindings\":[],\"yoloEnabled\":true,"
            + "\"contextWindow\":100000,\"compaction\":null}";

    assertEquals(expected, codec.encode(request));
    assertEquals(request, codec.decode(expected));
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> codec.decode(expected).toolBindings().add(request.toolBindings().getFirst()));
  }

  @Test
  void roundTripsFrozenSubagentBindings() {
    ModelInvocationRequest base = platformModelRequest();
    ModelInvocationRequest request =
        new ModelInvocationRequest(
            base.environmentName(),
            base.providerRequest(),
            base.toolBindings(),
            base.skillBindings(),
            List.of(new SubagentBinding("reviewer", "Review changes")),
            base.yoloEnabled(),
            base.contextWindow(),
            null);

    String encoded = codec.encode(request);

    assertTrue(
        encoded.contains(
            "\"subagentBindings\":[{\"name\":\"reviewer\","
                + "\"description\":\"Review changes\"}]"));
    assertEquals(request, codec.decode(encoded));
  }

  /** 仅 platform 请求的 null route 保持显式，不获得 fallback 身份。 */
  @Test
  void roundTripsPlatformRequestWithExplicitNullRoutes() {
    ModelInvocationRequest request = platformModelRequest();
    String encoded = codec.encode(request);

    assertEquals(request, codec.decode(encoded));
    assertNull(codec.decode(encoded).environmentName());
    assertNull(codec.decode(encoded).toolBindings().getFirst().environmentName());
    assertNull(codec.decode(encoded).skillBindings().getFirst().sourceEnvironmentName());
    assertEquals(
        "{\"environmentName\":null,\"providerRequest\":",
        encoded.substring(0, encoded.indexOf('{', 1)));
  }

  /** 压缩请求 wire：tokensBefore 是数值（nonNegativeLong），ids 是规范十进制字符串。 */
  @Test
  void roundTripsCompactionRequestWithNumericTokensBefore() {
    ModelInvocationRequest base = environmentModelRequest();
    ModelInvocationRequest request =
        new ModelInvocationRequest(
            base.environmentName(),
            providerRequest(),
            List.of(),
            List.of(),
            false,
            100_000,
            new CompactionRequest(
                CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, 2L, 4L, null));

    String encoded = codec.encode(request);
    assertTrue(
        encoded.contains(
            "\"compaction\":{\"phase\":\"FULL\",\"trigger\":\"THRESHOLD\","
                + "\"tokensBefore\":500,\"firstKeptEntryId\":\"2\",\"cutEntryId\":\"4\","
                + "\"turnPrefixStartEntryId\":null}"),
        encoded);
    assertEquals(request, codec.decode(encoded));
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));

    // 非数值 tokensBefore / 非规范 id 严格拒绝。
    ObjectNode object = (ObjectNode) codec.encodeNode(request);
    ObjectNode compaction = (ObjectNode) object.get("compaction");
    compaction.put("tokensBefore", "500");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(object));
    compaction.put("tokensBefore", 500);
    compaction.put("firstKeptEntryId", "007");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(object));
  }

  /** 严格 parser 设置在嵌套 decode 之前拒绝畸形的顶层文档。 */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    String json = codec.encode(environmentModelRequest());
    String duplicate =
        json.replace(
            "\"environmentName\":\"" + ENVIRONMENT_ID + "\"",
            "\"environmentName\":\""
                + ENVIRONMENT_ID
                + "\",\"environmentName\":\""
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
    environmentType.put("environmentName", 1);
    assertInvalid(environmentType);

    ObjectNode environmentCanonical = encodedNode();
    environmentCanonical.put("environmentName", ENVIRONMENT_ID.value().toUpperCase());
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
    firstSkill(skillEnvironmentType).put("sourceEnvironmentName", false);
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
            "\"sourceEnvironmentName\":\"" + ENVIRONMENT_ID + "\"",
            "\"sourceEnvironmentName\":\"" + OTHER_ENVIRONMENT_ID + "\""));
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
