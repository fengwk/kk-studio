package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderService;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.util.List;

/** Catalog 分页参数的显式 400 边界；不依赖全局 IllegalArgumentException 兜底。 */
class StudioCatalogPaginationContractTest {

  private AgentDefinitionService agentDefinitionService;
  private AgentModelService agentModelService;
  private AgentProviderService agentProviderService;
  private McpServerService mcpServerService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    agentDefinitionService = mock(AgentDefinitionService.class);
    agentModelService = mock(AgentModelService.class);
    agentProviderService = mock(AgentProviderService.class);
    mcpServerService = mock(McpServerService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudioAgentDefinitionController(agentDefinitionService),
                new StudioAgentModelController(agentModelService),
                new StudioAgentProviderController(agentProviderService),
                new StudioMcpServerController(mcpServerService))
            .setControllerAdvice(
                new ApiResponseStatusAdvice(),
                new StudioDomainErrorAdvice(new StudioMessageService()))
            .build();
  }

  @Test
  void rejectsOutOfRangePageNumberOnEveryCatalogEndpoint() throws Exception {
    assertRejected("/api/agents", "pageNumber", "0");
    assertRejected("/api/models", "pageNumber", "0");
    assertRejected("/api/providers", "pageNumber", "0");
    assertRejected("/api/mcp-servers", "pageNumber", "0");
    verifyNoServiceCalls();
  }

  @Test
  void rejectsOutOfRangePageSizeOnEveryCatalogEndpoint() throws Exception {
    assertRejected("/api/agents", "pageSize", "0");
    assertRejected("/api/agents", "pageSize", "1001");
    assertRejected("/api/models", "pageSize", "0");
    assertRejected("/api/models", "pageSize", "1001");
    assertRejected("/api/providers", "pageSize", "0");
    assertRejected("/api/providers", "pageSize", "1001");
    assertRejected("/api/mcp-servers", "pageSize", "0");
    assertRejected("/api/mcp-servers", "pageSize", "1001");
    verifyNoServiceCalls();
  }

  @Test
  void rejectsPageNumberBeyondIntegerRangeWithoutReachingService() throws Exception {
    mockMvc
        .perform(get("/api/agents").param("pageNumber", "2147483648"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("pageNumber"))
        .andExpect(jsonPath("$.errors.detail").value(containsString("pageNumber")));
    verifyNoServiceCalls();
  }

  @Test
  void maxPageNumberUsesSaturatedLongOffset() throws Exception {
    when(agentDefinitionService.pageAgents(any()))
        .thenAnswer(
            invocation -> {
              PageQuery query = invocation.getArgument(0);
              return new Page<>(query.getPageNumber(), query.getPageSize(), List.of(), 0L);
            });

    mockMvc
        .perform(
            get("/api/agents")
                .param("pageNumber", String.valueOf(Integer.MAX_VALUE))
                .param("pageSize", String.valueOf(PageQuery.MAX_PAGE_SIZE)))
        .andExpect(status().isOk());

    ArgumentCaptor<PageQuery> query = ArgumentCaptor.forClass(PageQuery.class);
    verify(agentDefinitionService).pageAgents(query.capture());
    assertEquals((Integer.MAX_VALUE - 1L) * PageQuery.MAX_PAGE_SIZE, query.getValue().getOffset());
  }

  @Test
  void rejectsNonNumericPageParameterAsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/agents").param("pageSize", "invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.detail").value(containsString("pageSize")));
    verifyNoServiceCalls();
  }

  private void assertRejected(String path, String parameter, String value) throws Exception {
    mockMvc
        .perform(get(path).param(parameter, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("pagination"))
        .andExpect(jsonPath("$.errors.detail").value(containsString(parameter)));
  }

  private void verifyNoServiceCalls() {
    verifyNoInteractions(
        agentDefinitionService, agentModelService, agentProviderService, mcpServerService);
  }
}
