package fun.fengwk.kkstudio.web.advice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.project.service.IssueEditingService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** 实际组合根中 Project 与 Issue 的领域及临时旧异常路由。 */
@AutoConfigureMockMvc
class ProjectControllerAdviceIntegrationTest extends WebPostgresTestSupport {
  private static final String ID = "00000000-0000-0000-0000-0000000000a1";
  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectService projectService;
  @MockitoBean private IssueEditingService issueEditingService;

  @Test
  void projectDomainAndLegacyErrorsUseTheCorrectAdvice() throws Exception {
    // 真实 MVC 装配应把领域异常交给统一 advice，旧 Harness 异常仍由临时 advice 去敏。
    doThrow(DomainException.notFound("project")).when(projectService).getProject(any());
    mockMvc
        .perform(get("/api/projects/{id}", ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.errors.resource").value("project"));
    doThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_VERSION, "sensitive"))
        .when(projectService)
        .getProject(any());
    mockMvc
        .perform(get("/api/projects/{id}", ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROJECT_RUNTIME_CONFLICT"))
        .andExpect(jsonPath("$.errors.detail").value("Issue runtime conflict occurred"));
    mockMvc
        .perform(get("/api/projects/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROJECT_VALIDATION_ERROR"));
  }

  @Test
  void issueDomainErrorsUseTheUnifiedAdvice() throws Exception {
    // 同一路由分别确认 CAS 坐标和校验错误没有被旧 advice 截获。
    String request = "{\"expectedVersion\":\"1\",\"title\":\"title\"}";
    doThrow(DomainException.versionConflict("issue", "1", "2"))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/issues/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("1"))
        .andExpect(jsonPath("$.errors.actualVersion").value("2"));
    doThrow(DomainException.validation("title", "title is invalid"))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/issues/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("title"));
    doThrow(DomainException.duplicate("issue_activity", "duplicate"))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/issues/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));
  }
}
