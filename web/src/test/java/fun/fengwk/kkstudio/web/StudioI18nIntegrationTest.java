package fun.fengwk.kkstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** 真实 Spring MVC 区域设置绑定与错误通知的集成覆盖。 */
@AutoConfigureMockMvc
class StudioI18nIntegrationTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void domainAdviceUsesRequestLocaleAndEnglishFallback() throws Exception {
    assertDomainMessage("en-US", "The chat was not found.");
    assertDomainMessage("zh-CN", "未找到 对话。");
    assertDomainMessage(null, "The chat was not found.");
    assertDomainMessage("fr-FR", "The chat was not found.");
  }

  @Test
  void responseStatusAdviceUsesRequestLocaleOnTheSameChatController() throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/chats")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"i18n-test\",\"agentName\":\"default-assistant\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String chatId =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

    assertResponseStatusMessage(chatId, "zh-CN", "请求无效。");
    assertResponseStatusMessage(chatId, "en-US", "The request is invalid.");
    assertResponseStatusMessage(chatId, "fr-FR", "The request is invalid.");
  }

  private void assertDomainMessage(String language, String expectedMessage) throws Exception {
    MockHttpServletRequestBuilder request = get("/api/chats/00000000-0000-0000-0000-000000000999");
    if (language != null) {
      request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
    }

    mockMvc
        .perform(request)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.title").value(expectedMessage))
        .andExpect(jsonPath("$.detail").value("chat not found"));
  }

  private void assertResponseStatusMessage(String chatId, String language, String expectedMessage)
      throws Exception {
    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/threads", "not-a-number")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.title").value(expectedMessage))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.detail").value("sessionId must be a canonical UUID: not-a-number"));
  }
}
