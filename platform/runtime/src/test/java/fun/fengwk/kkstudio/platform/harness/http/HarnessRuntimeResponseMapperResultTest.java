package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.NOW;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.id;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.snapshot;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.withVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessManualCompactionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessStoppedThreadReceiptDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.Map;

class HarnessRuntimeResponseMapperResultTest {

  @Test
  void passesStoppedManualCompactionReasonThroughAsStableName() {
    // DTO 只透传稳定枚举名；前端负责本地化，API 不新增私有 reason 枚举。
    HarnessManualCompactionDTO dto =
        HarnessRuntimeResponseMapper.toManualCompactionDto(
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.THREAD_STOPPED));

    assertFalse(dto.getAvailable());
    assertEquals("THREAD_STOPPED", dto.getDisabledReason());
  }

  @Test
  void projectsAcceptedCommandsAndRejectsThreadMismatch() {
    // Durable acceptance 结果与随后回读 snapshot 必须属于同一 Thread。
    AcceptedCommands accepted =
        new AcceptedCommands(
            HarnessRuntimeTestFixtures.session(),
            HarnessRuntimeTestFixtures.rootEntry(),
            HarnessRuntimeTestFixtures.thread(id(1)),
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            true,
            false);

    HarnessAcceptedCommandsDTO dto =
        HarnessRuntimeResponseMapper.toAcceptedCommandsDto(
            accepted, HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals(idText(1), dto.getSession().getSessionId());
    assertEquals("session", dto.getSession().getName());
    assertEquals(NOW, dto.getSession().getCreatedAt());
    assertEquals(EntryType.ROOT, dto.getRootEntry().getEntryType());
    assertEquals(idText(1), dto.getThread().getThreadId());
    assertEquals("thread", dto.getThread().getName());
    assertEquals(idText(50), dto.getAcceptedCommands().getFirst().getIdempotencyKey());
    assertTrue(dto.isReplayed());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toAcceptedCommandsDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
  }

  @Test
  void projectsCompactResultNullableChildThreadAndRejectsThreadMismatch() {
    // Resolver 拒绝时 childThreadId 显式 null；接受时保持子 Thread id。
    CompactThreadResult rejected =
        new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null);
    HarnessThreadCompactResultDTO rejectedDto =
        HarnessRuntimeResponseMapper.toCompactResultDto(
            rejected, HarnessRuntimeTestFixtures.idleSnapshot());
    assertEquals(idText(2), rejectedDto.getTurnStartEntryId());
    assertNull(rejectedDto.getChildThreadId());

    CompactThreadResult accepted =
        new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), id(10));
    assertEquals(
        idText(10),
        HarnessRuntimeResponseMapper.toCompactResultDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot())
            .getChildThreadId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toCompactResultDto(
                accepted, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
  }

  @Test
  void projectsStoppedAndReplayedStopResultsWithReceipts() {
    // STOPPED/REPLAYED 两态与每节点聚合回执由 StopResult 唯一决定。
    StoppedThreadReceipt receipt =
        new StoppedThreadReceipt(
            id(1),
            id(5),
            id(6),
            3,
            List.of(
                new CancelledThreadInput(
                    1, id(50), new UserMessageCommandPayload(AgentMessage.user("first"))),
                new CancelledThreadInput(
                    2,
                    id(51),
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(
                                ResourceMessageContent.media(id(70), "report.txt", "preview")))))));
    StopResult stopped =
        new StopResult(false, HarnessRuntimeTestFixtures.thread(id(1)), List.of(receipt));
    HarnessThreadStopResultDTO stoppedDto =
        HarnessRuntimeResponseMapper.toStopResultDto(
            stopped, HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals(HarnessThreadStopResultDTO.Status.STOPPED, stoppedDto.getStatus());
    assertEquals(1, stoppedDto.getStoppedThreads().size());
    HarnessStoppedThreadReceiptDTO receiptDto = stoppedDto.getStoppedThreads().getFirst();
    assertEquals(idText(1), receiptDto.getThreadId());
    assertEquals(idText(5), receiptDto.getStopRequestId());
    assertEquals(idText(6), receiptDto.getStoppedTurnEndEntryId());
    assertEquals(3, receiptDto.getCancelledCommandCount());
    assertEquals(2, receiptDto.getCancelledInputs().size());
    assertEquals(
        ThreadCommandType.USER_MESSAGE, receiptDto.getCancelledInputs().getFirst().getType());
    assertTrue(
        JsonValues.write(receiptDto.getCancelledInputs().getFirst().getPayload())
            .contains("first"));
    assertTrue(
        JsonValues.write(receiptDto.getCancelledInputs().get(1).getPayload()).contains(idText(70)));

    StopResult replay = new StopResult(true, stopped.thread(), stopped.stoppedThreads());
    HarnessThreadStopResultDTO replayDto =
        HarnessRuntimeResponseMapper.toStopResultDto(
            replay, HarnessRuntimeTestFixtures.idleSnapshot());
    assertEquals(HarnessThreadStopResultDTO.Status.REPLAYED, replayDto.getStatus());
    assertEquals(1, replayDto.getStoppedThreads().size());
  }

  /** 测试意图：snapshot 必须把该 Thread 自己的持久 Stop 回执一并投影，供刷新或未打开子页恢复草稿。 */
  @Test
  void projectsStopReceiptsFromSnapshot() {
    StoppedThreadReceipt receipt =
        new StoppedThreadReceipt(
            id(1),
            id(5),
            null,
            1,
            List.of(
                new CancelledThreadInput(
                    1, id(50), new UserMessageCommandPayload(AgentMessage.user("draft")))));
    ThreadSnapshot snapshot =
        snapshot(
            HarnessRuntimeTestFixtures.thread(id(1)),
            new EntryPath(List.of(HarnessRuntimeTestFixtures.rootEntry())),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(receipt),
            0,
            false,
            List.of(),
            false);

    HarnessThreadSnapshotDTO dto = HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, Map.of());

    assertEquals(1, dto.getStopReceipts().size());
    HarnessStoppedThreadReceiptDTO receiptDto = dto.getStopReceipts().getFirst();
    assertEquals(idText(1), receiptDto.getThreadId());
    assertEquals(idText(5), receiptDto.getStopRequestId());
    assertNull(receiptDto.getStoppedTurnEndEntryId());
    assertTrue(
        JsonValues.write(receiptDto.getCancelledInputs().getFirst().getPayload())
            .contains("draft"));
  }

  @Test
  void rejectsStopSnapshotIdentityAndVersionMismatch() {
    // stop 后回读必须同时匹配 Thread id 与 version，不能返回另一时刻的快照。
    StopResult result = new StopResult(false, HarnessRuntimeTestFixtures.thread(id(1)), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toStopResultDto(
                result, HarnessRuntimeTestFixtures.idleSnapshot(id(99))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeResponseMapper.toStopResultDto(
                result, withVersion(HarnessRuntimeTestFixtures.idleSnapshot(), 4)));
  }
}
