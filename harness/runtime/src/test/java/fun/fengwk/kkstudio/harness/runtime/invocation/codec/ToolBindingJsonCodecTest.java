package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;

/** 覆盖 HOST、DECLARATIVE 与 ENVIRONMENT_CAPABILITY backend 的严格 ToolBinding wire。 */
class ToolBindingJsonCodecTest {

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final AgentToolDefinitionJsonCodec definitionCodec = new AgentToolDefinitionJsonCodec();

  /** 精确 JSON 既证明字段顺序 deterministic，又在 round-trip 时重新执行 backend 不变式。 */
  @Test
  void roundTripsEveryBackendWithCanonicalJson() {
    ToolBinding environment = binding(AgentToolBackend.ENVIRONMENT_CAPABILITY);
    String environmentJson =
        "{\"definition\":"
            + definitionCodec.encode(environment.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + "}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding host = binding(AgentToolBackend.HOST);
    String hostJson =
        "{\"definition\":"
            + definitionCodec.encode(host.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environment\":null}";
    assertEquals(hostJson, codec.encode(host));
    assertEquals(host, codec.decode(hostJson));
    assertNull(codec.decode(hostJson).environment());

    ToolBinding declarative = binding(AgentToolBackend.DECLARATIVE);
    String declarativeJson =
        "{\"definition\":"
            + definitionCodec.encode(declarative.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}"
            + ",\"environment\":null}";
    assertEquals(declarativeJson, codec.encode(declarative));
    assertEquals(declarative, codec.decode(declarativeJson));
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(binding(AgentToolBackend.HOST));
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

  /** 顶层字段必须恰好是 definition/contributor/environment。 */
  @Test
  void rejectsUnknownMissingAndWrongTypeTopLevelFields() {
    ToolBinding environment = binding(AgentToolBackend.ENVIRONMENT_CAPABILITY);
    String definition = definitionCodec.encode(environment.definition());
    String valid =
        "{\"definition\":"
            + definition
            + ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + "}";

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(valid.replace(",\"environment\":" + environmentJson(ENVIRONMENT_ID), "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(environmentJson(ENVIRONMENT_ID), "1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "1")));
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
    ToolBinding host = binding(AgentToolBackend.HOST);
    String definition = definitionCodec.encode(host.definition());

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[],\"unknown\":true}"
                    + ",\"environment\":null}"));

    // 缺少 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\"}"
                    + ",\"environment\":null}"));

    // stateAccesses 元素格式非法
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"INVALID\"}]}"
                    + ",\"environment\":null}"));
  }

  /** 构造器不变式必须在 codec 解码边界生效，而不是把非法 provenance 带入执行器。 */
  @Test
  void rejectsInvalidBackendPayloadCombinations() {
    ToolBinding host = binding(AgentToolBackend.HOST);
    ToolBinding declarative = binding(AgentToolBackend.DECLARATIVE);
    String declarativeContributorJson =
        "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}";
    String emptyContributorJson =
        "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}";

    // HOST 携带 environment
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(host.definition())
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environment\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + "}"));

    // DECLARATIVE 携带 environment
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(declarative.definition())
                    + ",\"contributor\":"
                    + declarativeContributorJson
                    + ",\"environment\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + "}"));

    // ENVIRONMENT_CAPABILITY environment 为 null
    ToolBinding env = binding(AgentToolBackend.ENVIRONMENT_CAPABILITY);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(env.definition())
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environment\":null}"));

    // HOST 声明 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(host.definition())
                    + ",\"contributor\":"
                    + declarativeContributorJson
                    + ",\"environment\":null}"));
  }

  private static String environmentJson(EnvironmentBinding binding) {
    return "{\"name\":\""
        + binding.environmentName().value()
        + "\",\"workspacePath\":\""
        + binding.workspacePath()
        + "\"}";
  }
}
