package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.math.BigDecimal;
import java.util.List;

/** final Input payload codec 必须按 durable type 严格还原最终 payload。 */
class ThreadInputPayloadJsonCodecTest {

  private final ThreadInputPayloadJsonCodec codec = new ThreadInputPayloadJsonCodec();

  @Test
  void roundTripsConfigAndFinalUserEntryPayload() throws Exception {
    RuntimeConfigSnapshot config = new RuntimeConfigJsonCodec().decode(canonicalConfigJson());
    RuntimeConfigInputPayload configPayload =
        new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, config);
    assertEquals(
        configPayload, codec.decode(ThreadInputType.SET_AGENT, codec.encode(configPayload)));
    RuntimeConfigInputPayload environmentPayload =
        new RuntimeConfigInputPayload(ThreadInputType.SET_ENVIRONMENT, config);
    assertEquals(
        environmentPayload,
        codec.decode(ThreadInputType.SET_ENVIRONMENT, codec.encode(environmentPayload)));

    RuntimeEntryInputPayload messagePayload =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.<AgentMessageContent>of(new TextMessageContent("hello")))));
    RuntimeEntryInputPayload decoded =
        assertInstanceOf(
            RuntimeEntryInputPayload.class,
            codec.decode(ThreadInputType.USER_MESSAGE, codec.encode(messagePayload)));
    assertEquals(messagePayload, decoded);

    RuntimeEntryInputPayload customPayload =
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE,
            new CustomMessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.SYSTEM,
                    List.<AgentMessageContent>of(new TextMessageContent("instruction")))));
    assertEquals(
        customPayload, codec.decode(ThreadInputType.CUSTOM_MESSAGE, codec.encode(customPayload)));
  }

  @Test
  void rejectsDuplicateTrailingAndWrongPayloadType() throws Exception {
    String config = canonicalConfigJson();
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadInputType.SET_MODEL, config + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                ThreadInputType.SET_YOLO,
                config.replaceFirst("\\\"agent\\\":", "\\\"agent\\\":{},\\\"agent\\\":")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.USER_MESSAGE, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.SET_AGENT, "[]"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadInputType.SET_AGENT, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadInputType.CUSTOM_MESSAGE, "{\"message\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigInputPayload(
                ThreadInputType.USER_MESSAGE, new RuntimeConfigJsonCodec().decode(config)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeEntryInputPayload(
                ThreadInputType.SET_MODEL,
                new MessageEntryPayload(
                    new AgentMessage(
                        AgentMessageRole.USER,
                        List.<AgentMessageContent>of(new TextMessageContent("bad"))))));
  }

  private static String canonicalConfigJson() {
    ModelDescriptor descriptor =
        new ModelDescriptor(
            2L,
            3L,
            ProviderType.OPENAI,
            "gpt-test",
            true,
            false,
            new ModelPricing(
                "USD",
                "default",
                "standard",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(
            new AgentSnapshot(1L, "agent", "system"),
            new ModelSnapshot(
                descriptor,
                new ModelVariant("default", null, null, null, null, null, null, List.of(), null)),
            null,
            List.of(),
            List.of(),
            false);
    return new RuntimeConfigJsonCodec().encode(snapshot);
  }
}
