package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;

import java.time.Instant;

/** PostgreSQL regression for immutable name references carried by USER and CUSTOM inputs. */
class HarnessThreadCommandServicePostgresqlIntegrationTest extends PostgresSpringTestSupport {

  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private HarnessThreadCommandServiceImpl commandService;

  @Test
  void userAndCustomInputsPersistExactTurnSettingsAndReplayWithoutLiveResolution() {
    HarnessThread thread =
        transactions.createThread("message-settings", Instant.parse("2026-07-24T00:00:00Z"));

    HarnessThreadMessageCreateDTO user = new HarnessThreadMessageCreateDTO();
    user.setContent("hello");
    user.setAgentName("agent-a");
    user.setEnvironmentName("environment-a");
    user.setYoloEnabled(true);
    user.setClientMessageId("user-1");
    user.setExpectedExecutionEpoch(thread.executionEpoch());

    HarnessThreadInputDTO userInput =
        commandService.submitUserMessage(Long.toString(thread.id()), user);
    RuntimeEntryInputPayload userPayload =
        assertInstanceOf(
            RuntimeEntryInputPayload.class,
            INPUT_CODEC.decode(ThreadInputType.USER_MESSAGE, userInput.getPayloadJson()));
    MessageEntryPayload userEntry =
        assertInstanceOf(MessageEntryPayload.class, userPayload.payload());
    assertEquals(ThreadInputType.USER_MESSAGE, userPayload.type());
    assertEquals(new TurnSettings("agent-a", "environment-a", true), userEntry.turnSettings());
    assertEquals(1L, userInput.getSequence());

    HarnessThreadCustomMessageCreateDTO custom = new HarnessThreadCustomMessageCreateDTO();
    custom.setRole("system");
    custom.setContent("rules");
    custom.setAgentName("agent-b");
    custom.setEnvironmentName(null);
    custom.setYoloEnabled(false);
    custom.setClientMessageId("custom-1");
    custom.setExpectedExecutionEpoch(thread.executionEpoch());

    HarnessThreadInputDTO customInput =
        commandService.submitCustomMessage(Long.toString(thread.id()), custom);
    RuntimeEntryInputPayload customPayload =
        assertInstanceOf(
            RuntimeEntryInputPayload.class,
            INPUT_CODEC.decode(ThreadInputType.CUSTOM_MESSAGE, customInput.getPayloadJson()));
    CustomMessageEntryPayload customEntry =
        assertInstanceOf(CustomMessageEntryPayload.class, customPayload.payload());
    assertEquals(ThreadInputType.CUSTOM_MESSAGE, customPayload.type());
    assertEquals(new TurnSettings("agent-b", null, false), customEntry.turnSettings());
    assertEquals(2L, customInput.getSequence());

    HarnessThreadMessageCreateDTO replay = new HarnessThreadMessageCreateDTO();
    replay.setContent("different body");
    replay.setAgentName(" invalid ");
    replay.setEnvironmentName(" invalid ");
    replay.setYoloEnabled(null);
    replay.setClientMessageId("user-1");
    replay.setExpectedExecutionEpoch(null);

    HarnessThreadInputDTO replayed =
        commandService.submitUserMessage(Long.toString(thread.id()), replay);
    assertEquals(userInput.getInputId(), replayed.getInputId());
    assertEquals(userInput.getPayloadJson(), replayed.getPayloadJson());
  }

  @Test
  void rejectsNonCanonicalTurnNamesBeforeWritingAnInput() {
    HarnessThread thread =
        transactions.createThread("invalid-settings", Instant.parse("2026-07-24T00:00:00Z"));
    HarnessThreadMessageCreateDTO request = new HarnessThreadMessageCreateDTO();
    request.setContent("hello");
    request.setAgentName(" agent ");
    request.setYoloEnabled(false);
    request.setClientMessageId("invalid");
    request.setExpectedExecutionEpoch(thread.executionEpoch());

    assertThrows(
        IllegalArgumentException.class,
        () -> commandService.submitUserMessage(Long.toString(thread.id()), request));
  }
}
