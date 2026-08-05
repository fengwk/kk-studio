package fun.fengwk.kkstudio.share.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

/** HarnessRuntime wire DTO 契约测试：字段形状、Instant 时间戳与不可变空列表默认值。 */
class HarnessRuntimeDtoContractTest {

  @Test
  void threadDtoExposesTheNewWireProjection() {
    HarnessThreadDTO dto = new HarnessThreadDTO();
    assertNull(dto.getThreadId());

    dto.setThreadId("1");
    dto.setSessionId("2");
    dto.setHeadEntryId("3");
    dto.setYoloEnabled(true);
    dto.setNextCommandSequence("4");
    dto.setRevision("5");
    dto.setStatus("IDLE");
    dto.setProcessing(false);
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    dto.setBranchSettings(settings);
    Instant createTime = Instant.parse("2026-01-01T00:00:00Z");
    Instant updateTime = Instant.parse("2026-01-01T00:00:01Z");
    dto.setCreateTime(createTime);
    dto.setUpdateTime(updateTime);

    assertEquals("1", dto.getThreadId());
    assertEquals("2", dto.getSessionId());
    assertEquals("3", dto.getHeadEntryId());
    assertEquals(Boolean.TRUE, dto.getYoloEnabled());
    assertEquals("4", dto.getNextCommandSequence());
    assertEquals("5", dto.getRevision());
    assertEquals("IDLE", dto.getStatus());
    assertEquals(Boolean.FALSE, dto.getProcessing());
    assertEquals(settings, dto.getBranchSettings());
    assertEquals(createTime, dto.getCreateTime());
    assertEquals(updateTime, dto.getUpdateTime());
    assertEquals(Instant.class, fieldType(HarnessThreadDTO.class, "createTime"));
    assertEquals(Instant.class, fieldType(HarnessThreadDTO.class, "updateTime"));
  }

  @Test
  void sessionEntryDtoCarriesSessionIdAndInstantCreateTime() {
    HarnessSessionEntryDTO dto = new HarnessSessionEntryDTO();
    dto.setEntryId("1");
    dto.setSessionId("2");
    dto.setParentEntryId("3");
    dto.setEntryType("MESSAGE");
    dto.setPayloadJson("{}");
    Instant createTime = Instant.parse("2026-01-01T00:00:00Z");
    dto.setCreateTime(createTime);

    assertEquals("1", dto.getEntryId());
    assertEquals("2", dto.getSessionId());
    assertEquals("3", dto.getParentEntryId());
    assertEquals("MESSAGE", dto.getEntryType());
    assertEquals("{}", dto.getPayloadJson());
    assertEquals(createTime, dto.getCreateTime());
    assertEquals(Instant.class, fieldType(HarnessSessionEntryDTO.class, "createTime"));
  }

  @Test
  void snapshotListsDefaultToImmutableEmptyAndCarrySingularModelInvocation() {
    HarnessThreadSnapshotDTO dto = new HarnessThreadSnapshotDTO();
    assertTrue(dto.getEntries().isEmpty());
    assertTrue(dto.getQueuedCommands().isEmpty());
    assertNull(dto.getModelInvocation());
    assertTrue(dto.getToolInvocations().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> dto.getEntries().add(null));
    assertThrows(UnsupportedOperationException.class, () -> dto.getQueuedCommands().add(null));
    assertThrows(UnsupportedOperationException.class, () -> dto.getToolInvocations().add(null));

    dto.setRevision("7");
    HarnessThreadDTO thread = new HarnessThreadDTO();
    dto.setThread(thread);
    ModelInvocationDTO invocation = new ModelInvocationDTO();
    dto.setModelInvocation(invocation);
    dto.setToolInvocations(List.of(new ToolInvocationDTO()));

    assertEquals("7", dto.getRevision());
    assertEquals(thread, dto.getThread());
    assertEquals(invocation, dto.getModelInvocation());
    assertEquals(1, dto.getToolInvocations().size());
    assertTrue(dto.getEntries().isEmpty()); // 未设置的列表保持默认不可变空
  }

  @Test
  void requestDtosDefaultListsToImmutableEmptyWhereSuitable() {
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    assertTrue(settings.getActiveTools().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> settings.getActiveTools().add(null));

    HarnessThreadCommandBatchDTO batch = new HarnessThreadCommandBatchDTO();
    assertTrue(batch.getCommands().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> batch.getCommands().add(null));

    // 命令 DTO 的可选字段保持 null 默认：mapper 以 null 区分 forbidden 与未设置。
    HarnessThreadCommandCreateDTO command = new HarnessThreadCommandCreateDTO();
    assertNull(command.getActiveTools());

    HarnessThreadCommandCreateDTO typed = new HarnessThreadCommandCreateDTO();
    typed.setType("USER_MESSAGE");
    typed.setClientCommandId("cmd-1");
    typed.setContent("hello");
    typed.setRole("USER");
    typed.setAgentName("assistant");
    typed.setModel(new HarnessModelSelectionDTO());
    typed.setThinkingLevel("low");
    typed.setYoloEnabled(false);
    typed.setEnvironmentId(null);
    assertEquals("USER_MESSAGE", typed.getType());
    assertEquals("cmd-1", typed.getClientCommandId());
    assertEquals("hello", typed.getContent());
    assertEquals("USER", typed.getRole());
    assertEquals("assistant", typed.getAgentName());
    assertEquals("low", typed.getThinkingLevel());
    assertEquals(Boolean.FALSE, typed.getYoloEnabled());
    assertNull(typed.getEnvironmentId());
  }

  @Test
  void invocationDtosExposeTheSevenTableProjectionWithInstantTimestamps() {
    ModelInvocationDTO model = new ModelInvocationDTO();
    model.setId("10");
    model.setThreadId("1");
    model.setTurnStartEntryId("2");
    model.setBasisHeadEntryId("2");
    model.setStatus("RUNNING");
    model.setAttempt(1);
    model.setStreamCheckpointJson(null);
    model.setResultJson(null);
    model.setErrorJson(null);
    model.setResultEntryId(null);
    Instant createTime = Instant.parse("2026-01-01T00:00:00Z");
    Instant updateTime = Instant.parse("2026-01-01T00:00:02Z");
    model.setCreateTime(createTime);
    model.setUpdateTime(updateTime);

    assertEquals("10", model.getId());
    assertEquals("1", model.getThreadId());
    assertEquals("2", model.getTurnStartEntryId());
    assertEquals("2", model.getBasisHeadEntryId());
    assertEquals("RUNNING", model.getStatus());
    assertEquals(1, model.getAttempt());
    assertNull(model.getStreamCheckpointJson());
    assertNull(model.getResultEntryId());
    assertEquals(createTime, model.getCreateTime());
    assertEquals(updateTime, model.getUpdateTime());
    assertEquals(Instant.class, fieldType(ModelInvocationDTO.class, "createTime"));
    assertEquals(Instant.class, fieldType(ModelInvocationDTO.class, "updateTime"));

    ToolInvocationDTO tool = new ToolInvocationDTO();
    tool.setId("20");
    tool.setModelInvocationId("10");
    tool.setAssistantEntryId("4");
    tool.setOrdinal(1);
    tool.setStatus("READY");
    tool.setToolCallId("call_1");
    tool.setToolName("bash");
    tool.setToolVersion("1.0");
    tool.setToolType("SHELL");
    tool.setEnvironmentId("env-1");
    tool.setArgumentsJson("{}");
    tool.setApprovalJson(null);
    tool.setResultJson(null);
    tool.setErrorJson(null);
    tool.setResultEntryId(null);
    tool.setCreateTime(createTime);
    tool.setUpdateTime(updateTime);

    assertEquals("20", tool.getId());
    assertEquals("10", tool.getModelInvocationId());
    assertEquals("4", tool.getAssistantEntryId());
    assertEquals(1, tool.getOrdinal());
    assertEquals("READY", tool.getStatus());
    assertEquals("call_1", tool.getToolCallId());
    assertEquals("bash", tool.getToolName());
    assertEquals("1.0", tool.getToolVersion());
    assertEquals("SHELL", tool.getToolType());
    assertEquals("env-1", tool.getEnvironmentId());
    assertEquals("{}", tool.getArgumentsJson());
    assertNull(tool.getApprovalJson());
    assertNull(tool.getResultEntryId());
    assertEquals(createTime, tool.getCreateTime());
    assertEquals(updateTime, tool.getUpdateTime());
    assertEquals(Instant.class, fieldType(ToolInvocationDTO.class, "createTime"));
    assertEquals(Instant.class, fieldType(ToolInvocationDTO.class, "updateTime"));
  }

  @Test
  void headUpdateStopAndApprovalDtosCarryCasCursors() {
    HarnessThreadHeadUpdateDTO head = new HarnessThreadHeadUpdateDTO();
    head.setTargetEntryId("3");
    head.setExpectedRevision("2");
    assertEquals("3", head.getTargetEntryId());
    assertEquals("2", head.getExpectedRevision());

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setStopRequestId("stop-1");
    stop.setExpectedRevision("5");
    assertEquals("stop-1", stop.getStopRequestId());
    assertEquals("5", stop.getExpectedRevision());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecision("ALLOW");
    approval.setDecisionId("decision-1");
    approval.setActor("alice");
    approval.setReason("looks safe");
    assertEquals("ALLOW", approval.getDecision());
    assertEquals("decision-1", approval.getDecisionId());
    assertEquals("alice", approval.getActor());
    assertEquals("looks safe", approval.getReason());
  }

  @Test
  void stopResultDtoCarriesStopOutcomeAndThread() {
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    assertNull(dto.getThread());
    assertNull(dto.getStoppedTurnEndEntryId());

    dto.setStatus("STOPPED");
    HarnessThreadDTO thread = new HarnessThreadDTO();
    dto.setThread(thread);
    dto.setStoppedTurnEndEntryId("9");
    dto.setCancelledCommandCount(2);

    assertEquals("STOPPED", dto.getStatus());
    assertEquals(thread, dto.getThread());
    assertEquals("9", dto.getStoppedTurnEndEntryId());
    assertEquals(2, dto.getCancelledCommandCount());
    assertInstanceOf(HarnessThreadDTO.class, dto.getThread());
  }

  private static Class<?> fieldType(Class<?> type, String name) {
    try {
      Field field = type.getDeclaredField(name);
      return field.getType();
    } catch (NoSuchFieldException error) {
      throw new AssertionError("missing field " + name + " on " + type.getSimpleName(), error);
    }
  }
}
