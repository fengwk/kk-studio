package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.Set;

/** 离线可选的运行时 tool 目录 HTTP 契约。 */
@AutoConfigureMockMvc
class StudioToolCatalogControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void exposesOnlySelectableToolMetadataByName() throws Exception {
    // 工具条目的唯一身份是模型可见 name，对外暴露确切字段集合（name、description、environmentSupport），不泄露内部身份与实现字段。
    MvcResult result =
        mockMvc
            .perform(get("/api/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.name == 'read')].name").value("read"))
            .andExpect(jsonPath("$.data[?(@.name == 'read')].description").isNotEmpty())
            .andExpect(jsonPath("$.data[?(@.name == 'task')]").doesNotExist())
            .andReturn();

    JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
    JsonNode readEntry = null;
    for (JsonNode entry : root.path("data")) {
      if ("read".equals(entry.path("name").asText())) {
        readEntry = entry;
        break;
      }
    }

    assertNotNull(readEntry, "expected catalog entry for 'read'");
    assertEquals(
        Set.of("name", "description", "environmentSupport"), Set.copyOf(readEntry.propertyNames()));
  }
}
