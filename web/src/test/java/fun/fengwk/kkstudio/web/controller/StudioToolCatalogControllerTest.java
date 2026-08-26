package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** 离线可选的运行时 tool 目录 HTTP 契约。 */
@AutoConfigureMockMvc
class StudioToolCatalogControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  @Test
  void exposesOnlySelectableToolMetadata() throws Exception {
    mockMvc
        .perform(get("/api/ai/catalog/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name == 'read')].id").value("base.read"))
        .andExpect(jsonPath("$.data[?(@.name == 'read')].backend").value("ENVIRONMENT_CAPABILITY"))
        .andExpect(jsonPath("$.data[?(@.name == 'read')].version").value("1"))
        .andExpect(jsonPath("$.data[?(@.name == 'read')].description").isNotEmpty())
        .andExpect(jsonPath("$.data[?(@.name == 'load_skill')]").doesNotExist());
  }
}
