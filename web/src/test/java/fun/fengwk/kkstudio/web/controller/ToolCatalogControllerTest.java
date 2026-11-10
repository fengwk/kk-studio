package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import java.util.HashSet;
import java.util.Set;

/** 离线可选的运行时 tool 目录 HTTP 契约。 */
@AutoConfigureMockMvc
class ToolCatalogControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void exposesOnlySelectableToolMetadataByName() throws Exception {
    // 工具条目的唯一身份是模型可见 name，对外暴露确切字段集合（name、description、environmentSupport、provider），
    // 不泄露内部身份与实现字段。
    MvcResult result =
        mockMvc
            .perform(get("/api/platform/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'read')].name").value("read"))
            .andExpect(jsonPath("$[?(@.name == 'read')].description").isNotEmpty())
            .andExpect(jsonPath("$[?(@.name == 'read')].provider.kind").value("BUILTIN"))
            .andExpect(jsonPath("$[?(@.name == 'task')]").doesNotExist())
            .andReturn();

    JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
    JsonNode readEntry = null;
    for (JsonNode entry : root) {
      if ("read".equals(entry.path("name").asText())) {
        readEntry = entry;
        break;
      }
    }

    assertNotNull(readEntry, "expected catalog entry for 'read'");
    assertEquals(
        Set.of("name", "description", "environmentSupport", "provider"),
        Set.copyOf(readEntry.propertyNames()));
    assertEquals("builtin", readEntry.path("provider").path("id").asText());
    assertEquals("Built-in", readEntry.path("provider").path("name").asText());
  }

  /** 目录上每个条目都带 provider；完整上下文中四类 kind 至少覆盖 BUILTIN 与 APP。 */
  @Test
  void everyEntryCarriesAProvider() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/api/platform/tools")).andExpect(status().isOk()).andReturn();

    JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
    Set<String> kinds = new HashSet<>();
    for (JsonNode entry : root) {
      JsonNode provider = entry.path("provider");
      assertTrue(provider.isObject(), "provider must be an object: " + entry);
      assertTrue(
          provider.path("kind").isTextual() && !provider.path("id").asText().isBlank(),
          "provider must carry kind and id: " + provider);
      kinds.add(provider.path("kind").asText());
    }
    assertTrue(kinds.contains("BUILTIN"), "builtin tools must be present: " + kinds);
    assertTrue(kinds.contains("APP"), "host app tools must be present: " + kinds);
  }
}
