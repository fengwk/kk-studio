package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;

/** 严格的 ToolBinding wire JSON 编解码测试。 */
class ToolBindingJsonCodecTest {

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final AgentToolDefinitionJsonCodec definitionCodec = new AgentToolDefinitionJsonCodec();

  /** 精确 JSON 证明字段顺序 deterministic，并在 round-trip 时保证 binding 状态无损。 */
  @Test
  void roundTripsEveryBindingShapeWithCanonicalJson() {
    ToolBinding environment = binding(true);
    String environmentJson =
        "{\"definition\":"
            + definitionCodec.encode(environment.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentRequired\":true"
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + "}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding host = binding(false);
    String hostJson =
        "{\"definition\":"
            + definitionCodec.encode(host.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentRequired\":false"
            + ",\"environment\":null}";
    assertEquals(hostJson, codec.encode(host));
    assertEquals(host, codec.decode(hostJson));
    assertFalse(codec.decode(hostJson).environmentRequired());
    assertNull(codec.decode(hostJson).environment());

    ToolBinding declarative = binding(false, true);
    String declarativeJson =
        "{\"definition\":"
            + definitionCodec.encode(declarative.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}"
            + ",\"environmentRequired\":false"
            + ",\"environment\":null}";
    assertEquals(declarativeJson, codec.encode(declarative));
    assertEquals(declarative, codec.decode(declarativeJson));

    ToolBinding stateWithEnv = binding(true, true);
    String stateWithEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(stateWithEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}"
            + ",\"environmentRequired\":true"
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + "}";
    assertEquals(stateWithEnvJson, codec.encode(stateWithEnv));
    assertEquals(stateWithEnv, codec.decode(stateWithEnvJson));
    assertTrue(codec.decode(stateWithEnvJson).environmentRequired());
    assertEquals(ENVIRONMENT_ID, codec.decode(stateWithEnvJson).environment());
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(binding(false));
    String duplicate =
        json.replace(
            "\"environment\":null",
            "\"environment\":null,\"environment\":{\"name\":\"env-1\",\"workspacePath\":\".\"}");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 顶层字段必须恰好是 definition/contributor/environmentRequired/environment。 */
  @Test
  void rejectsUnknownMissingAndWrongTypeTopLevelFields() {
    ToolBinding environment = binding(true);
    String definition = definitionCodec.encode(environment.definition());
    String valid =
        "{\"definition\":"
            + definition
            + ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentRequired\":true"
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + "}";

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));

    // 缺少必要字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"environmentRequired\":true", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(valid.replace(",\"environment\":" + environmentJson(ENVIRONMENT_ID), "")));

    // 字段类型错误
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace("\"environmentRequired\":true", "\"environmentRequired\":\"true\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(environmentJson(ENVIRONMENT_ID), "1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "null")));
  }

  /** Contributor 对象内部字段校验：必须是 contributorId/localName/stateAccesses。 */
  @Test
  void rejectsInvalidContributorFields() {
    ToolBinding host = binding(false);
    String definition = definitionCodec.encode(host.definition());

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[],\"unknown\":true}"
                    + ",\"environmentRequired\":false"
                    + ",\"environment\":null}"));

    // 缺少 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\"}"
                    + ",\"environmentRequired\":false"
                    + ",\"environment\":null}"));

    // stateAccesses 元素格式非法
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"INVALID\"}]}"
                    + ",\"environmentRequired\":false"
                    + ",\"environment\":null}"));
  }

  /** 解码边界必须重新校验 environmentRequired 与 environment 的一致性。 */
  @Test
  void rejectsMismatchedEnvironmentRequiredAndEnvironmentCombinations() {
    ToolBinding host = binding(false);
    String definition = definitionCodec.encode(host.definition());
    String emptyContributorJson =
        "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}";

    // environmentRequired 为 true 但 environment 为 null
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentRequired\":true"
                    + ",\"environment\":null}"));

    // environmentRequired 为 false 但 environment 非 null
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentRequired\":false"
                    + ",\"environment\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + "}"));
  }

  private static String environmentJson(EnvironmentBinding binding) {
    return "{\"environmentId\":\""
        + binding.environmentId()
        + "\",\"workspacePath\":\""
        + binding.workspacePath()
        + "\"}";
  }
}
