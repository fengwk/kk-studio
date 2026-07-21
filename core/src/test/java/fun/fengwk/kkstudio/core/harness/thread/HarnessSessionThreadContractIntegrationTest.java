package fun.fengwk.kkstudio.core.harness.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;

import java.time.LocalDateTime;
import java.util.List;

/** Session/Main Thread and public Thread command contract against the durable H2 store. */
@SpringBootTest
@Transactional
class HarnessSessionThreadContractIntegrationTest {

  @Autowired private HarnessSessionCommandService sessionCommandService;
  @Autowired private HarnessSessionQueryService sessionQueryService;
  @Autowired private HarnessThreadCommandService threadCommandService;
  @Autowired private HarnessThreadQueryService threadQueryService;
  @Autowired private HarnessThreadMapper threadMapper;

  /** Session creation commits its root configuration chain and stable Main Thread as one unit. */
  @Test
  void createsSessionRootEntriesAndStableMainThreadAtomically() {
    int rootCount = sessionQueryService.listRootSessions().size();
    HarnessSessionDTO created = sessionCommandService.createSession(session("contract", true));
    assertTrue(created.getSessionId().matches("\\d+"));
    assertTrue(created.getMainThreadId().matches("\\d+"));
    assertEquals(created.getSessionId(), created.getRootSessionId());
    assertEquals(0, created.getDepth());
    assertEquals(rootCount + 1, sessionQueryService.listRootSessions().size());

    HarnessSessionDTO reloaded = sessionQueryService.getSession(created.getSessionId());
    assertEquals(created.getMainThreadId(), reloaded.getMainThreadId());
    List<HarnessSessionEntryDTO> entries = sessionQueryService.listEntries(created.getSessionId());
    assertEquals(
        List.of("root"), entries.stream().map(HarnessSessionEntryDTO::getEntryType).toList());
    assertNull(entries.get(0).getParentEntryId());

    HarnessThreadDTO mainThread = threadQueryService.getThread(created.getMainThreadId());
    assertEquals(created.getSessionId(), mainThread.getSessionId());
    assertEquals(entries.get(0).getEntryId(), mainThread.getHeadEntryId());
    assertEquals("IDLE", mainThread.getStatus());
    assertEquals(0L, mainThread.getInputSequence());
    assertTrue(Boolean.TRUE.equals(mainThread.getYoloEnabled()));
    assertNull(mainThread.getActiveAgentDefinitionId());
  }

  /** Branches may only use an Entry from their own Session and queue replay is payload-safe. */
  @Test
  void validatesBranchMembershipAndPreservesInputAndStopReceipts() {
    HarnessSessionDTO firstSession = sessionCommandService.createSession(session("first", false));
    HarnessSessionDTO otherSession = sessionCommandService.createSession(session("other", false));
    String firstThreadId = firstSession.getMainThreadId();
    String firstEntryId =
        sessionQueryService.listEntries(firstSession.getSessionId()).get(0).getEntryId();
    String otherEntryId =
        sessionQueryService.listEntries(otherSession.getSessionId()).get(0).getEntryId();

    HarnessThreadCreateDTO branchRequest = new HarnessThreadCreateDTO();
    branchRequest.setFromEntryId(firstEntryId);
    HarnessThreadDTO branch =
        threadCommandService.createThread(firstSession.getSessionId(), branchRequest);
    assertEquals(firstSession.getSessionId(), branch.getSessionId());
    assertEquals(firstEntryId, branch.getHeadEntryId());
    assertEquals("IDLE", branch.getStatus());
    assertNull(branch.getActiveAgentDefinitionId());
    assertNull(branch.getModelId());
    assertFalse(Boolean.TRUE.equals(branch.getYoloEnabled()));

    HarnessThreadCreateDTO crossSessionRequest = new HarnessThreadCreateDTO();
    crossSessionRequest.setFromEntryId(otherEntryId);
    assertThrows(
        IllegalStateException.class,
        () -> threadCommandService.createThread(firstSession.getSessionId(), crossSessionRequest));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            threadCommandService.createThread(
                firstSession.getSessionId(), threadRequest("999999999999")));

    HarnessThreadInputDTO first =
        threadCommandService.submitUserMessage(firstThreadId, userMessage("message-1", "one"));
    HarnessThreadInputDTO replay =
        threadCommandService.submitUserMessage(firstThreadId, userMessage("message-1", "one"));
    assertEquals(first.getInputId(), replay.getInputId());
    assertEquals("QUEUED", first.getStatus());
    assertNull(first.getResolvedAt());
    assertThrows(
        IllegalStateException.class,
        () ->
            threadCommandService.submitUserMessage(
                firstThreadId, userMessage("message-1", "changed")));

    HarnessThreadInputDTO custom =
        threadCommandService.submitCustomMessage(
            firstThreadId, customMessage("custom-1", "system", "two"));
    List<HarnessThreadInputDTO> queued = threadQueryService.listInputs(firstThreadId);
    assertEquals(List.of(1L, 2L), queued.stream().map(HarnessThreadInputDTO::getSequence).toList());
    assertEquals(
        List.of("USER_MESSAGE", "CUSTOM_MESSAGE"),
        queued.stream().map(HarnessThreadInputDTO::getInputType).toList());
    assertEquals(custom.getInputId(), queued.get(1).getInputId());

    HarnessThreadStopResultDTO stopped =
        threadCommandService.stop(firstThreadId, stopRequest("stop-1"));
    assertNotNull(stopped.getStopId());
    assertEquals(List.of("one", "two"), stopped.getRestoredMessages());
    assertEquals(2, stopped.getCancelledInputs().size());
    assertTrue(
        stopped.getCancelledInputs().stream()
            .allMatch(input -> "CANCELLED".equals(input.getStatus())));
    assertTrue(
        stopped.getCancelledInputs().stream().allMatch(input -> input.getResolvedAt() != null));
    assertTrue(
        stopped.getCancelledInputs().stream()
            .allMatch(input -> stopped.getStopId().equals(input.getCancelledByStopId())));

    HarnessThreadStopResultDTO replayedStop =
        threadCommandService.stop(firstThreadId, stopRequest("stop-1"));
    assertEquals(stopped.getStopId(), replayedStop.getStopId());
    assertEquals(stopped.getRestoredMessages(), replayedStop.getRestoredMessages());
    assertEquals(
        stopped.getCancelledInputs().stream().map(HarnessThreadInputDTO::getInputId).toList(),
        replayedStop.getCancelledInputs().stream().map(HarnessThreadInputDTO::getInputId).toList());

    long firstThread = Long.parseLong(firstThreadId);
    threadMapper.updateStatusDirect(firstThread, "FAILED", LocalDateTime.now());
    HarnessThreadInputDTO resumed =
        threadCommandService.submitUserMessage(
            firstThreadId, userMessage("message-after-failure", "resume"));
    assertEquals("QUEUED", resumed.getStatus());
    assertEquals("RUNNING", threadQueryService.getThread(firstThreadId).getStatus());

    assertThrows(
        IllegalArgumentException.class, () -> threadQueryService.getThread("999999999999"));
    assertFalse(threadQueryService.listBySession(firstSession.getSessionId()).isEmpty());
  }

  /**
   * 公共命令服务：分支 Thread 按路径最后 AGENT_CHANGE 初始化身份，并读取当前 Definition model/variant。
   *
   * <p>说明：配置 Input 需 Processor harvest 才会落 Entry；此处通过 durable transaction 服务的测试已覆盖 harvest
   * 路径。本用例仅验证 createThread 在已有 agent_change Entry 链上的投影（由 integration 事务测试构造完整链路后，这里做公共 API 的无
   * Agent 回归；有 Agent 路径见 HarnessThreadTransactionServiceIntegrationTest）。
   */
  @Test
  void createThreadKeepsAgentlessBranchWhenPathHasNoAgentChange() {
    HarnessSessionDTO session = sessionCommandService.createSession(session("branch-null", false));
    String rootEntryId =
        sessionQueryService.listEntries(session.getSessionId()).get(0).getEntryId();
    HarnessThreadDTO branch =
        threadCommandService.createThread(session.getSessionId(), threadRequest(rootEntryId));
    assertNull(branch.getActiveAgentDefinitionId());
    assertNull(branch.getActiveAgentName());
    assertNull(branch.getModelId());
    assertNull(branch.getVariant());
    assertFalse(Boolean.TRUE.equals(branch.getYoloEnabled()));
    assertEquals(rootEntryId, branch.getHeadEntryId());
  }

  private static HarnessSessionCreateDTO session(String title, boolean yolo) {
    HarnessSessionCreateDTO request = new HarnessSessionCreateDTO();
    request.setTitle(title);
    request.setYoloEnabled(yolo);
    return request;
  }

  private static HarnessThreadCreateDTO threadRequest(String fromEntryId) {
    HarnessThreadCreateDTO request = new HarnessThreadCreateDTO();
    request.setFromEntryId(fromEntryId);
    return request;
  }

  private static HarnessThreadMessageCreateDTO userMessage(String clientMessageId, String content) {
    HarnessThreadMessageCreateDTO request = new HarnessThreadMessageCreateDTO();
    request.setClientMessageId(clientMessageId);
    request.setContent(content);
    return request;
  }

  private static HarnessThreadCustomMessageCreateDTO customMessage(
      String clientMessageId, String role, String content) {
    HarnessThreadCustomMessageCreateDTO request = new HarnessThreadCustomMessageCreateDTO();
    request.setClientMessageId(clientMessageId);
    request.setRole(role);
    request.setContent(content);
    return request;
  }

  private static HarnessThreadStopDTO stopRequest(String clientRequestId) {
    HarnessThreadStopDTO request = new HarnessThreadStopDTO();
    request.setClientRequestId(clientRequestId);
    return request;
  }
}
