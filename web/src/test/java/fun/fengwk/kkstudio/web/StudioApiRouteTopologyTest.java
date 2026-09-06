package fun.fengwk.kkstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 完整 Spring MVC 拓扑测试：旧 API 前缀和已删除的公开存储入口不得重新注册。 */
@AutoConfigureMockMvc
class StudioApiRouteTopologyTest extends WebPostgresTestSupport {

  private static final String UUID_TEXT = "00000000-0000-0000-0000-000000000001";
  private static final String SHA256_TEXT =
      "0000000000000000000000000000000000000000000000000000000000000000";

  @Autowired private MockMvc mockMvc;

  /** 测试意图：验证旧 Harness、Environment、Chat 与 S3 wire 路径在完整应用中统一返回 404。 */
  @Test
  void legacyApiTopologyIsNotRegistered() throws Exception {
    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/ai/runtime/sessions/{sessionId}/threads", UUID_TEXT))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/ai/runtime/threads/{threadId}/snapshot", UUID_TEXT))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/ai/runtime/resources/{sha256}", SHA256_TEXT))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/environments")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/environment/daemon/v1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/chat")).andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/s3/presigned-uploads").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/s3/presigned-downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }
}
