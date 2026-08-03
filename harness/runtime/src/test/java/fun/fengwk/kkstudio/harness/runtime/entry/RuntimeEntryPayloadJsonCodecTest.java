package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.math.BigDecimal;
import java.util.List;

/** Strict Entry codec coverage for turn references and role-specific payloads. */
class RuntimeEntryPayloadJsonCodecTest {

  private static final TurnSettings SETTINGS = new TurnSettings("agent", true);
  private final RuntimeEntryPayloadJsonCodec codec = new RuntimeEntryPayloadJsonCodec();

  @Test
  void roundTripsUserAndCustomReferences() {
    MessageEntryPayload user =
        new MessageEntryPayload(message(AgentMessageRole.USER, "hello"), SETTINGS, null);
    CustomMessageEntryPayload custom =
        new CustomMessageEntryPayload(message(AgentMessageRole.SYSTEM, "system"), SETTINGS);

    assertEquals(user, codec.decode(EntryType.MESSAGE, codec.encode(user)));
    assertEquals(custom, codec.decode(EntryType.CUSTOM_MESSAGE, codec.encode(custom)));
    assertTrue(codec.encode(user).contains("\"turnSettings\""));
  }

  @Test
  void roundTripsAssistantWithoutARequestReference() {
    MessageEntryPayload assistant =
        new MessageEntryPayload(
            message(AgentMessageRole.ASSISTANT, "answer"),
            null,
            metadata(ProviderStopReason.COMPLETED));

    assertEquals(assistant, codec.decode(EntryType.MESSAGE, codec.encode(assistant)));
    assertTrue(codec.encode(assistant).contains("\"turnSettings\":null"));
  }

  @Test
  void rejectsMissingOrUnknownReferenceFieldsAndWrongRoles() {
    String encoded =
        codec.encode(
            new MessageEntryPayload(message(AgentMessageRole.USER, "hello"), SETTINGS, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.MESSAGE, encoded.replace("\"yoloEnabled\":true", "\"extra\":true")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.MESSAGE, "{\"message\":{},\"assistantMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustomMessageEntryPayload(
                message(AgentMessageRole.ASSISTANT, "assistant"), SETTINGS));
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata(ProviderStopReason reason) {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(2));
    return new AssistantMessageMetadata(reason, usage, cost);
  }
}
