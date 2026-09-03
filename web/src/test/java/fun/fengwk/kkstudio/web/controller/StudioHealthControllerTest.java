package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

@AutoConfigureMockMvc
public class StudioHealthControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  /** 存活探针必须在不访问数据库、S3 或 ComfyUI 的情况下稳定返回成功。 */
  @Test
  public void shouldReturnOk() throws Exception {
    mockMvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().string(""));
  }
}
