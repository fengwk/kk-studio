package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@link ProviderReplayStateJsonCodec} 严格性、深拷贝、toString 脱敏与 Jackson @JsonIgnore 验证。 */
class ProviderReplayStateJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().disable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES);
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ProviderReplayStateJsonCodec codec = new ProviderReplayStateJsonCodec();

  @Test
  void roundTripsCompleteReplayState() {
    ProviderReplayState state = sampleState();
    String encoded = codec.encode(state);
    ProviderReplayState decoded = codec.decode(encoded);

    assertEquals(state, decoded);
    assertEquals(encoded, codec.encode(decoded));
  }

  @Test
  void defensiveDeepCopyOnConstructionAndAccess() {
    ObjectNode payload = NODES.objectNode();
    payload.put("secret", "key123");
    payload.put("count", 42);

    ProviderReplayState state =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            new ProviderReplayAffinity(
                ProviderType.ANTHROPIC, "anthropic", UUID.randomUUID(), "claude-3-5-sonnet"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            payload);

    // 修改传入的原始 ObjectNode，不应影响 state 内部
    payload.put("secret", "mutated");
    assertEquals("key123", state.payload().path("secret").asText());

    // 修改获取到的 payload ObjectNode，不应影响 state 内部
    ((ObjectNode) state.payload()).put("secret", "mutated-again");
    assertEquals("key123", state.payload().path("secret").asText());
    assertNotSame(state.payload(), state.payload());
  }

  @Test
  void toStringDoesNotLeakPayload() {
    ObjectNode payload = NODES.objectNode();
    payload.put("internal_token", "super_secret_payload_token_that_must_not_leak");
    payload.put("data", "sensitive");

    ProviderReplayState state =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            new ProviderReplayAffinity(
                ProviderType.OPENAI_RESPONSES, "minimax", UUID.randomUUID(), "minimax-m2"),
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            payload);

    String repr = state.toString();
    assertFalse(repr.contains("super_secret_payload_token_that_must_not_leak"));
    assertFalse(repr.contains("sensitive"));
    assertTrue(repr.contains("format="));
    assertTrue(repr.contains("payloadSize="));
  }

  @Test
  void jacksonSerializationIgnoresReplayStateOnCarrierEntities() throws Exception {
    ProviderReplayState state = sampleState();

    // 1. ProviderMessage
    ProviderMessage msg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("reply")), state);
    String msgJson = OBJECT_MAPPER.writeValueAsString(msg);
    assertFalse(msgJson.contains("replayState"));
    assertFalse(msgJson.contains("sourcePrefixHash"));

    // 2. Entry (ASSISTANT message)
    MessagePayload msgPayload =
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE,
                new ModelUsage(1, 1, 0, 0, 0, 0, 0),
                new ModelCost(
                    "USD",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)),
            null);
    Entry entry =
        new Entry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            msgPayload,
            Instant.parse("2026-01-01T00:00:00Z"),
            state);
    String entryJson = OBJECT_MAPPER.writeValueAsString(entry);
    assertFalse(entryJson.contains("providerReplayState"));
    assertFalse(entryJson.contains("sourcePrefixHash"));

    // 3. ModelInvocation
    ModelInvocation invocation =
        new ModelInvocation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ModelRequestSpec(
                ProviderType.OPENAI,
                new ModelDescriptor(
                    "provider",
                    "model",
                    Set.of(ModelInputModality.TEXT),
                    true,
                    true,
                    new ModelPricing(
                        "USD",
                        "standard",
                        "standard",
                        BigDecimal.ONE,
                        "1",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO)),
                new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ProviderCacheControl.none()),
            ModelInvocationStatus.SUCCEEDED,
            1,
            null,
            new ProviderResponse(
                "ok",
                "",
                List.of(),
                GenerationStopReason.COMPLETE,
                new ModelUsage(1, 1, 0, 0, 0, 0, 0),
                new ModelCost(
                    "USD",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO),
                null,
                null,
                "{}",
                List.of()),
            null,
            null,
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            state);
    String invocationJson = OBJECT_MAPPER.writeValueAsString(invocation);
    assertFalse(invocationJson.contains("providerReplayState"));
    assertFalse(invocationJson.contains("sourcePrefixHash"));
  }

  @Test
  void rejectsUnknownField() {
    ObjectNode node = (ObjectNode) codec.encodeNode(sampleState());
    node.put("unknownField", "surprise");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));
  }

  @Test
  void rejectsDuplicateField() {
    String jsonWithDup =
        """
        {
          "format": "anthropic_messages",
          "affinity": {
            "providerType": "anthropic",
            "providerName": "anthropic",
            "connectionGenerationId": "00000000-0000-0000-0000-000000000001",
            "modelName": "claude-3-5"
          },
          "sourcePrefixHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "payload": {"key":"val"},
          "format": "anthropic_messages"
        }
        """;
    assertThrows(IllegalArgumentException.class, () -> codec.decode(jsonWithDup));
  }

  @Test
  void rejectsInvalidHexHash() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                new ProviderReplayAffinity(
                    ProviderType.ANTHROPIC, "anthropic", UUID.randomUUID(), "model"),
                "short-hash",
                NODES.objectNode()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                new ProviderReplayAffinity(
                    ProviderType.ANTHROPIC, "anthropic", UUID.randomUUID(), "model"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeG",
                NODES.objectNode()));
  }

  @Test
  void rejectsMissingRootFields() {
    for (String field : List.of("format", "affinity", "sourcePrefixHash", "payload")) {
      ObjectNode node = (ObjectNode) codec.encodeNode(sampleState());
      node.remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));
    }
  }

  @Test
  void rejectsMissingAffinityFields() {
    for (String field :
        List.of("providerType", "providerName", "connectionGenerationId", "modelName")) {
      ObjectNode node = (ObjectNode) codec.encodeNode(sampleState());
      ((ObjectNode) node.get("affinity")).remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));
    }
  }

  @Test
  void rejectsInvalidAffinityValues() {
    // 非法 UUID
    ObjectNode badUuid = (ObjectNode) codec.encodeNode(sampleState());
    ((ObjectNode) badUuid.get("affinity")).put("connectionGenerationId", "not-a-valid-uuid");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(badUuid.toString()));

    // affinity 不是 Object
    ObjectNode badAffinityType = (ObjectNode) codec.encodeNode(sampleState());
    badAffinityType.put("affinity", "string");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(badAffinityType.toString()));
  }

  @Test
  void rejectsInvalidPayloadAndRootTypes() {
    // payload 不是 Object
    ObjectNode arrayPayload = (ObjectNode) codec.encodeNode(sampleState());
    arrayPayload.putArray("payload").add("item");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(arrayPayload.toString()));

    // root 不是 Object
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("\"string\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{malformed"));

    // null 检查
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
  }

  @Test
  void formatAndAffinityInvariants() {
    assertThrows(IllegalArgumentException.class, () -> ProviderReplayFormat.fromWireValue(null));
    assertThrows(IllegalArgumentException.class, () -> ProviderReplayFormat.fromWireValue("   "));
    assertThrows(
        IllegalArgumentException.class, () -> ProviderReplayFormat.fromWireValue("unknown_format"));
    for (ProviderReplayFormat format : ProviderReplayFormat.values()) {
      assertEquals(format, ProviderReplayFormat.fromWireValue(format.wireValue()));
      assertEquals(format, ProviderReplayFormat.fromWireValue(format.name()));
      assertEquals(format, ProviderReplayFormat.fromWireValue(format.name().toLowerCase()));
    }

    UUID genId = UUID.randomUUID();
    ProviderReplayAffinity affinity =
        new ProviderReplayAffinity(ProviderType.OPENAI, "p", genId, "m");
    assertTrue(affinity.toString().contains("providerType=OPENAI"));
    assertEquals(ProviderType.OPENAI, affinity.providerType());
    assertEquals("p", affinity.providerName());
    assertEquals(genId, affinity.connectionGenerationId());
    assertEquals("m", affinity.modelName());

    assertThrows(
        NullPointerException.class, () -> new ProviderReplayAffinity(null, "p", genId, "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderReplayAffinity(ProviderType.OPENAI, "", genId, "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderReplayAffinity(ProviderType.OPENAI, "  p  ", genId, "m"));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderReplayAffinity(ProviderType.OPENAI, "p", null, "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderReplayAffinity(ProviderType.OPENAI, "p", genId, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderReplayAffinity(ProviderType.OPENAI, "p", genId, "  m  "));
  }

  private ProviderReplayState sampleState() {
    ObjectNode payload = NODES.objectNode();
    payload.put("msg_id", "msg_12345");
    payload.putArray("stop_sequences").add("human:");
    return new ProviderReplayState(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        new ProviderReplayAffinity(
            ProviderType.ANTHROPIC,
            "anthropic",
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "claude-3-5-sonnet"),
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        payload);
  }
}
