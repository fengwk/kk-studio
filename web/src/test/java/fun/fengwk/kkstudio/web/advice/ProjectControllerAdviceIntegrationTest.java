package fun.fengwk.kkstudio.web.advice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import fun.fengwk.kkstudio.project.service.IssueEditingService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** 实际组合根中 Project 与 Issue 的统一 problem 响应。 */
@AutoConfigureMockMvc
class ProjectControllerAdviceIntegrationTest extends WebPostgresTestSupport {
  private static final String ID = "00000000-0000-0000-0000-0000000000a1";
  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectService projectService;
  @MockitoBean private IssueEditingService issueEditingService;

  @Test
  void projectDomainErrorsUseTheUnifiedAdvice() throws Exception {
    doThrow(DomainException.notFound("project")).when(projectService).getProject(any());
    mockMvc
        .perform(get("/api/project/projects/{id}", ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.detail").value("project not found"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());
    doThrow(DomainException.conflict("STALE_VERSION", "issue runtime conflict occurred"))
        .when(projectService)
        .getProject(any());
    mockMvc
        .perform(get("/api/project/projects/{id}", ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(jsonPath("$.reason").value("STALE_VERSION"))
        .andExpect(jsonPath("$.detail").value("issue runtime conflict occurred"));
    mockMvc
        .perform(get("/api/project/projects/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    // 路径 UUID 只接受规范小写形式，大写即使能被 JDK 解析也拒绝。
    mockMvc
        .perform(get("/api/project/projects/00000000-0000-0000-0000-0000000000A1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("projectId has an invalid value"));
  }

  @Test
  void requiredBodyPropertyIsValidationProblem() throws Exception {
    // F2 的必填校验在真实组合根中由统一 advice 转为 problem；请求不会触达服务。
    mockMvc
        .perform(
            post("/api/project/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"desc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("title is required"));
    verifyNoInteractions(projectService);
  }

  @Test
  void issueDomainErrorsUseTheUnifiedAdvice() throws Exception {
    // 同一路由分别确认 CAS 坐标和校验错误使用统一 problem。
    String request = "{\"expectedVersion\":\"1\",\"title\":\"title\"}";
    doThrow(DomainException.versionConflict("issue", 1L, 2L))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/project/issues/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("1"))
        .andExpect(jsonPath("$.errors.actualVersion").value("2"));
    doThrow(DomainException.validation("title", "title is invalid"))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/project/issues/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("title is invalid"))
        .andExpect(jsonPath("$.errors.resource").doesNotExist());
    doThrow(DomainException.duplicate("issue_activity", "duplicate"))
        .when(issueEditingService)
        .updateIssue(any(), anyLong(), any(), any());
    mockMvc
        .perform(
            put("/api/project/issues/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));
  }
}
