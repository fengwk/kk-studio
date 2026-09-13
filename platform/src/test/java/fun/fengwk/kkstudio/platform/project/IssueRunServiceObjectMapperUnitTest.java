package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.impl.IssueRunServiceImpl;

import java.util.UUID;

/**
 * 验证 IssueRunServiceImpl 在 ObjectMapper 序列化/反序列化发生故障时的 Fail-Fast 异常安全行为， 确保异常被脱敏并包装为标准
 * AiValidationException，而不返回空 JSON 串或吞掉错误。
 */
class IssueRunServiceObjectMapperUnitTest {

  @Test
  void testObjectMapperWriteFailureFailsFast() throws Exception {
    ObjectMapper mockedMapper = mock(ObjectMapper.class);
    when(mockedMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("Simulated serialize error") {});

    IssueRunServiceImpl service =
        new IssueRunServiceImpl(
            mock(ProjectRepository.class),
            mock(IssueRepository.class),
            mock(IssueDependencyRepository.class),
            mock(IssueInputRepository.class),
            mock(IssueRunRepository.class),
            mock(IssueRunSessionRepository.class),
            mock(IssueControllerWorkStore.class),
            mockedMapper);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                service.submitRun(
                    UUID.randomUUID(), "action-1", 0L, 0L, "Summary", "Verification"));
    assertEquals("Failed to serialize run result to JSON", ex.getMessage());
  }

  @Test
  void testObjectMapperReadFailureDuringReplayCheckFailsFast() throws Exception {
    ObjectMapper mockedMapper = mock(ObjectMapper.class);
    when(mockedMapper.writeValueAsString(any())).thenReturn("{\"summary\":\"A\"}");
    when(mockedMapper.readTree(anyString()))
        .thenThrow(new JsonProcessingException("Simulated parse error") {});

    IssueRunRepository runRepo = mock(IssueRunRepository.class);
    UUID runId = UUID.randomUUID();
    String actionId = "action-replay";

    IssueRun existing =
        IssueRun.builder()
            .id(runId)
            .role(IssueRunRole.EXECUTOR)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .observedSpecRevision(0L)
            .observedInputSequence(0L)
            .result("{\"different\":\"json\"}")
            .terminalActionId(actionId)
            .build();

    when(runRepo.findByTerminalActionId(actionId)).thenReturn(existing);

    IssueRunServiceImpl service =
        new IssueRunServiceImpl(
            mock(ProjectRepository.class),
            mock(IssueRepository.class),
            mock(IssueDependencyRepository.class),
            mock(IssueInputRepository.class),
            runRepo,
            mock(IssueRunSessionRepository.class),
            mock(IssueControllerWorkStore.class),
            mockedMapper);

    // 当 readTree 解析失败时，两 JSON 判定不等，fail-fast 抛出 Terminal action ID conflict
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> service.submitRun(runId, actionId, 0L, 0L, "Summary", "Verification"));
    assertEquals("Terminal action ID conflict", ex.getMessage());
  }
}
