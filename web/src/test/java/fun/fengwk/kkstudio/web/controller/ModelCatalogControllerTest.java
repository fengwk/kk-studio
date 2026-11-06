package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogCandidateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.platform.catalog.http.ModelCatalogController;
import fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;

import java.time.Instant;
import java.util.List;

/** 模型目录候选 API 的查询/刷新契约与页面字段透传。 */
class ModelCatalogControllerTest {

  private ModelCatalogService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(ModelCatalogService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ModelCatalogController(service))
            .setControllerAdvice(new ApiResponseStatusAdvice())
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.get()))
            .build();
  }

  @Test
  void pagesWithQueryCursorAndSize() throws Exception {
    when(service.page("gpt", "cursor-1", 5)).thenReturn(page(false));

    mockMvc
        .perform(
            get("/api/platform/model-catalog")
                .param("query", "gpt")
                .param("cursor", "cursor-1")
                .param("limit", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generation").value("7"))
        .andExpect(jsonPath("$.loading").value(false))
        .andExpect(jsonPath("$.items[0].id").value("models_dev:vendor:model"))
        .andExpect(jsonPath("$.items[0].pricingSupported").value(true))
        .andExpect(jsonPath("$.nextCursor").value("next"))
        .andExpect(jsonPath("$.data").doesNotExist());

    verify(service).page("gpt", "cursor-1", 5);
  }

  @Test
  void defaultsPageArgumentsAndRefreshesExplicitly() throws Exception {
    when(service.page(isNull(), isNull(), eq(20))).thenReturn(page(false));
    when(service.refresh()).thenReturn(page(true));

    mockMvc
        .perform(get("/api/platform/model-catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    mockMvc
        .perform(post("/api/platform/model-catalog/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.loading").value(true));

    verify(service).page(null, null, 20);
    verify(service).refresh();
  }

  private static ModelCatalogPageDTO page(boolean loading) {
    ModelCatalogCandidateDTO candidate = new ModelCatalogCandidateDTO();
    candidate.setId("models_dev:vendor:model");
    candidate.setProvider("vendor");
    candidate.setModelId("model");
    candidate.setName("Model");
    candidate.setPricingSupported(true);
    candidate.setSupportIssues(List.of());

    ModelCatalogPageDTO page = new ModelCatalogPageDTO();
    page.setGeneration("7");
    page.setFetchedAt(Instant.parse("2026-01-01T00:00:00Z"));
    page.setLoading(loading);
    page.setItems(List.of(candidate));
    page.setNextCursor("next");
    return page;
  }
}
