package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** HTTP contract for the offline-selectable runtime tool catalog. */
@AutoConfigureMockMvc
class StudioToolCatalogControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  @Test
  void exposesOnlySelectableToolMetadata() throws Exception {
    mockMvc
        .perform(get("/api/ai/catalog/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name == 'read')].version").value("1"))
        .andExpect(jsonPath("$.data[?(@.name == 'read')].description").isNotEmpty())
        .andExpect(jsonPath("$.data[?(@.name == 'load_skill')]").doesNotExist());
  }
}
