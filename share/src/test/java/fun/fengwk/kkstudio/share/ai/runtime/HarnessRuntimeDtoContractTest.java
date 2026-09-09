package fun.fengwk.kkstudio.share.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Harness Runtime HTTP DTO 契约：严格字段、严格字符串 cursor 与 compact/snapshot 投影。 */
class HarnessRuntimeDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void commandBatchDtoExposesOwnerTargetAndImmutableCommandDefaults() {
    HarnessCommandBatchDTO batch = new HarnessCommandBatchDTO();
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    batch.setOwner(owner);
    batch.setTarget(target);

    assertEquals(owner, batch.getOwner());
    assertEquals(target, batch.getTarget());
    assertTrue(batch.getCommands().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> batch.getCommands().add(null));
  }

  @Test
  void targetDtoTracksFieldPresenceSoExplicitNullCannotBypassForbiddenChecks() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("THREAD");
    target.setSessionId(null);
    target.setThreadId("00000000-0000-0000-0000-000000000001");
    target.setExpectedHeadEntryId("00000000-0000-0000-0000-000000000002");
    target.setExpectedNextCommandSequence("3");

    assertTrue(target.hasSessionIdField());
    assertNull(target.getSessionId());
    assertEquals("THREAD", target.getType());
    assertEquals("3", target.getExpectedNextCommandSequence());
  }

  @Test
  void commandDtoDoesNotExposeCustomMessagePayloadFields() {
    HarnessCommandCreateDTO command = new HarnessCommandCreateDTO();
    command.setType("USER_MESSAGE");
    command.setIdempotencyKey("00000000-0000-0000-0000-000000000001");
    command.setContents(List.of());

    assertEquals("USER_MESSAGE", command.getType());
    assertEquals("00000000-0000-0000-0000-000000000001", command.getIdempotencyKey());
    assertTrue(command.hasContentsField());
    assertFalse(command.hasWorkspacePathField());
  }

  @Test
  void unknownFieldsAreRejectedAtEveryNewRequestBoundary() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"owner":{},"target":{},"commands":[],"unknown":true}
                """,
                HarnessCommandBatchDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"THREAD","threadId":"00000000-0000-0000-0000-000000000001",
                 "expectedHeadEntryId":"00000000-0000-0000-0000-000000000002",
                 "expectedNextCommandSequence":"3","unknown":true}
                """,
                HarnessCommandTargetDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"SET_MODEL","idempotencyKey":"00000000-0000-0000-0000-000000000001",
                 "model":{"providerName":"p","modelName":"m","variant":"v","unknown":true}}
                """,
                HarnessCommandCreateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"expectedVersion":"0","unknown":true}
                """,
                HarnessThreadCompactDTO.class));
  }

  @Test
  void uuidAndCursorFieldsRejectJsonNumbersInsteadOfCoercingToStrings() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"THREAD","threadId":1,
                 "expectedHeadEntryId":"00000000-0000-0000-0000-000000000002",
                 "expectedNextCommandSequence":"3"}
                """,
                HarnessCommandTargetDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"expectedVersion":1}
                """,
                HarnessThreadCompactDTO.class));
  }

  @Test
  void nameUpdateDtoRejectsUnknownFieldsAndNonStringPrimitives() {
    // 意图：rename 请求体严格边界必须由共享 DTO 直接拒绝（未知字段 + 非字符串 JSON primitive），不进入 mapper。
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":"ok","unknown":true}
                """,
                HarnessNameUpdateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":42}
                """, HarnessNameUpdateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":["list"]}
                """,
                HarnessNameUpdateDTO.class));
  }

  @Test
  void nameUpdateDtoAcceptsOnlyTheNameFieldAndRoundsItBack() throws Exception {
    // 意图：唯一的合法 {name} 请求体必须精确 round-trip（长度/空白语义留给 Core 权威）。
    HarnessNameUpdateDTO dto =
        MAPPER.readValue(
            """
            {"name":"  display name  "}
            """,
            HarnessNameUpdateDTO.class);
    assertEquals("  display name  ", dto.getName());
  }

  @Test
  void snapshotCarriesManualCompactionAvailabilityAndNullableReason() {
    HarnessThreadSnapshotDTO snapshot = new HarnessThreadSnapshotDTO();
    HarnessManualCompactionDTO availability = new HarnessManualCompactionDTO();
    availability.setAvailable(true);
    availability.setDisabledReason(null);
    snapshot.setManualCompaction(availability);

    assertTrue(snapshot.getManualCompaction().getAvailable());
    assertNull(snapshot.getManualCompaction().getDisabledReason());
  }
}
