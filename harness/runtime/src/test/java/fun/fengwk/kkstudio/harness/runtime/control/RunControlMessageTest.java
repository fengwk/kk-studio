package fun.fengwk.kkstudio.harness.runtime.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link RunControlMessage} compact-constructor 对所有 status 的字段一致性不变量。 */
public class RunControlMessageTest {

  private static final AgentMessage USER_TEXT =
      new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi")));
  private static final Instant CREATED = Instant.parse("2026-07-15T00:00:00Z");
  private static final Instant CONSUMED = Instant.parse("2026-07-15T00:00:05Z");

  @Test
  public void pendingAcceptsNullOriginalRunAndConsumedFields() {
    RunControlMessage message =
        new RunControlMessage(
            1L,
            2L,
            null,
            RunControlKind.FOLLOW_UP,
            ControlConsumptionMode.ONE_AT_A_TIME,
            USER_TEXT,
            RunControlStatus.PENDING,
            null,
            null,
            CREATED,
            null);
    assertNull(message.originalRunId());
    assertNull(message.consumedAt());
  }

  @Test
  public void pendingRejectsAnyConsumedField() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                100L,
                null,
                CREATED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                200L,
                CREATED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                CONSUMED));
  }

  @Test
  public void consumedRequiresAllConsumedFields() {
    assertDoesNotThrow(
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CONSUMED,
                100L,
                200L,
                CREATED,
                CONSUMED));
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RunControlMessage(
                    1L,
                    2L,
                    100L,
                    RunControlKind.STEER,
                    ControlConsumptionMode.ONE_AT_A_TIME,
                    USER_TEXT,
                    RunControlStatus.CONSUMED,
                    null,
                    200L,
                    CREATED,
                    CONSUMED));
    assertTrue(exception.getMessage().contains("CONSUMED"));
  }

  @Test
  public void consumedRejectsNonPositiveIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CONSUMED,
                0L,
                200L,
                CREATED,
                CONSUMED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CONSUMED,
                100L,
                0L,
                CREATED,
                CONSUMED));
  }

  @Test
  public void promotedRequiresAllConsumedFieldsEvenWhenOriginalRunWasNull() {
    RunControlMessage message =
        new RunControlMessage(
            1L,
            2L,
            null,
            RunControlKind.FOLLOW_UP,
            ControlConsumptionMode.ALL,
            USER_TEXT,
            RunControlStatus.PROMOTED,
            77L,
            500L,
            CREATED,
            CONSUMED);
    assertNull(message.originalRunId());
    assertEquals(77L, message.consumedRunId());
    assertEquals(500L, message.consumedEntryId());
    assertEquals(CONSUMED, message.consumedAt());
  }

  @Test
  public void clearedOnlyRequiresConsumedAt() {
    RunControlMessage message =
        new RunControlMessage(
            1L,
            2L,
            100L,
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME,
            USER_TEXT,
            RunControlStatus.CLEARED,
            null,
            null,
            CREATED,
            CONSUMED);
    assertNull(message.consumedRunId());
    assertNull(message.consumedEntryId());
    assertEquals(CONSUMED, message.consumedAt());
  }

  @Test
  public void clearedRejectsFabricatedConsumedIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CLEARED,
                100L,
                null,
                CREATED,
                CONSUMED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CLEARED,
                null,
                200L,
                CREATED,
                CONSUMED));
  }

  @Test
  public void clearedRequiresConsumedAt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.CLEARED,
                null,
                null,
                CREATED,
                null));
  }

  @Test
  public void rejectsNonUserMessage() {
    AgentMessage assistant =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                100L,
                RunControlKind.STEER,
                ControlConsumptionMode.ONE_AT_A_TIME,
                assistant,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
  }

  @Test
  public void rejectsNonPositiveIdentifiers() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                0L,
                2L,
                null,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                0L,
                null,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                0L,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
  }

  @Test
  public void rejectsNullRequiredFields() {
    assertThrows(
        NullPointerException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                null,
                null,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                null,
                RunControlKind.FOLLOW_UP,
                null,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                null,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                null,
                RunControlStatus.PENDING,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                null,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                null,
                null,
                null,
                CREATED,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new RunControlMessage(
                1L,
                2L,
                null,
                RunControlKind.FOLLOW_UP,
                ControlConsumptionMode.ONE_AT_A_TIME,
                USER_TEXT,
                RunControlStatus.PENDING,
                null,
                null,
                null,
                null));
  }
}
