package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.catalog.http.McpServerController;
import fun.fengwk.kkstudio.platform.catalog.http.SkillCatalogController;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

class CatalogRequiredFieldsProblemWireTest {

  /** PUT 必须显式提供每个可编辑字段；缺失值在调用服务前返回 validation problem。 */
  @Test
  void mcpAndSkillUpdatesRejectMissingRequiredFields() throws Exception {
    McpServerService mcp = mock(McpServerService.class);
    SkillCatalogService skills = mock(SkillCatalogService.class);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new McpServerController(mcp), new SkillCatalogController(skills))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new ProblemAdvice(new MessageService()))
            .build();
    String base =
        "{\"expectedVersion\":\"0\",\"url\":\"https://example.com\","
            + "\"headers\":{\"operation\":\"KEEP\"},\"enabled\":true,\"timeoutMillis\":1000}";
    for (String missing :
        new String[] {
          base.replace("\"headers\":{\"operation\":\"KEEP\"},", ""),
          base.replace("\"enabled\":true,", ""),
          base.replace(",\"timeoutMillis\":1000", "")
        }) {
      mvc.perform(
              put("/api/platform/mcp-servers/example")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(missing))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("validation"));
    }
    mvc.perform(
            put("/api/platform/skill-packages/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(mcp, skills);
  }
}
