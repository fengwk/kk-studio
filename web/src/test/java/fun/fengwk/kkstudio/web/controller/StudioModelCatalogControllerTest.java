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

import fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogService;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;

import java.time.Instant;
import java.util.List;

/** 模型目录候选 API 的查询/刷新契约与页面字段透传。 */
class StudioModelCatalogControllerTest {

  private ModelCatalogService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(ModelCatalogService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StudioModelCatalogController(service))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.get()))
            .build();
  }

  @Test
  void pagesWithQueryCursorAndSize() throws Exception {
    when(service.page("gpt", "cursor-1", 5)).thenReturn(page(false));

    mockMvc
        .perform(
            get("/api/model-defaults")
                .param("query", "gpt")
                .param("cursor", "cursor-1")
                .param("pageSize", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source").value("models_dev_v2"))
        .andExpect(jsonPath("$.data.generation").value("7"))
        .andExpect(jsonPath("$.data.loading").value(false))
        .andExpect(jsonPath("$.data.items[0].id").value("models_dev_v2:vendor:model"))
        .andExpect(jsonPath("$.data.items[0].pricingSupported").value(true))
        .andExpect(jsonPath("$.data.nextCursor").value("next"));

    verify(service).page("gpt", "cursor-1", 5);
  }

  @Test
  void defaultsPageArgumentsAndRefreshesExplicitly() throws Exception {
    when(service.page(isNull(), isNull(), eq(20))).thenReturn(page(false));
    when(service.refresh()).thenReturn(page(true));

    mockMvc
        .perform(get("/api/model-defaults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1));

    mockMvc
        .perform(post("/api/model-defaults/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.loading").value(true));

    verify(service).page(null, null, 20);
    verify(service).refresh();
  }

  private static ModelCatalogPageDTO page(boolean loading) {
    ModelDefaultCandidateDTO candidate = new ModelDefaultCandidateDTO();
    candidate.setId("models_dev_v2:vendor:model");
    candidate.setProvider("vendor");
    candidate.setModelId("model");
    candidate.setName("Model");
    candidate.setPricingSupported(true);
    candidate.setSupportIssues(List.of());

    ModelCatalogPageDTO page = new ModelCatalogPageDTO();
    page.setSource(ModelCatalogSource.MODELS_DEV_V2);
    page.setGeneration("7");
    page.setFetchedAt(Instant.parse("2026-01-01T00:00:00Z"));
    page.setLoading(loading);
    page.setItems(List.of(candidate));
    page.setNextCursor("next");
    return page;
  }
}
