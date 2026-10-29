package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 Provider replay state 在 Runtime carrier 上仍是 attempt 内部事实：Jackson 序列化不得把原生 replay payload 泄露到
 * Entry、Message 或 ModelInvocation 的公共 wire。
 */
class ProviderReplayStateCarrierSerializationTest {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Test
  void jacksonSerializationIgnoresReplayStateOnCarrierEntities() throws Exception {
    ProviderReplayState state = sampleState();

    // 1. ProviderMessage
    ProviderMessage msg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("reply")), state);
    String msgJson = OBJECT_MAPPER.writeValueAsString(msg);
    assertFalse(msgJson.contains("replayState"));

    // 2. Entry (ASSISTANT message)
    MessagePayload msgPayload =
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 0L)),
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

    // 3. ModelInvocation
    ModelInvocation invocation =
        new ModelInvocation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ModelRequestSpec(
                ProviderType.OPENAI,
                new UUID(0L, 1L),
                new ModelDescriptor(
                    "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true),
                new ModelVariant("v1"),
                1024,
                "Test system instruction.",
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
                new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 0L),
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
  }

  private static ProviderReplayState sampleState() {
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
        payload);
  }
}
