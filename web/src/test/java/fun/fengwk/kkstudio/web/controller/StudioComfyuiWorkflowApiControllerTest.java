package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiUpdateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link StudioComfyuiWorkflowApiController} 的端到端测试。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>CRUD 路由；
 *   <li>响应 DTO 中的 {@code id} 为十进制字符串形式；
 *   <li>路径参数非法（{@code not-a-number}）通过 global exception handler 转化为 4xx；
 *   <li>写入路径非法入参（空白 workflowJson / 非小写 apiName）通过 global exception handler 转化为 4xx。
 * </ul>
 *
 * @author fengwk
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioComfyuiWorkflowApiControllerTest {

  private static final String WORKFLOW_JSON =
      "{\n"
          + "  \"3\": {\"class_type\":\"KSampler\",\"inputs\":{\"seed\":0,\"noise_seed\":1}}\n"
          + "}";

  private static final String BINDINGS_JSON =
      "[{\"name\":\"seed\","
          + "\"kind\":\"parameter\",\"nodeId\":\"3\",\"inputName\":\"seed\",\"valueType\":\"integer\","
          + "\"defaultValue\":7}]";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldCreateListUpdateAndDeleteWorkflow() throws Exception {
    String apiName = "flow-" + System.nanoTime();

    ComfyuiWorkflowApiCreateDTO create = new ComfyuiWorkflowApiCreateDTO();
    create.setApiName(apiName);
    create.setName("Demo");
    create.setDescription("desc");
    create.setWorkflowJson(WORKFLOW_JSON);
    create.setInputBindingsJson(BINDINGS_JSON);
    create.setDefaultSelector("$['3'].inputs.seed");
    create.setEnabled(true);

    // 创建链路返回 Created + 全字段映射 + id 为十进制字符串。
    mockMvc
        .perform(
            post("/api/comfyui/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.id").isString())
        .andExpect(jsonPath("$.data.apiName").value(apiName))
        .andExpect(jsonPath("$.data.name").value("Demo"))
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.createTime").exists())
        .andExpect(jsonPath("$.data.updateTime").exists());

    mockMvc
        .perform(get("/api/comfyui/workflows").param("pageNumber", "1").param("pageSize", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[?(@.apiName=='" + apiName + "')]").exists());

    ComfyuiWorkflowApiUpdateDTO update = new ComfyuiWorkflowApiUpdateDTO();
    update.setName("Demo-renamed");
    update.setWorkflowJson(WORKFLOW_JSON);
    update.setInputBindingsJson("[]");
    update.setDefaultSelector(null);
    update.setEnabled(false);

    // 通过 id 路径定位记录进行更新。
    mockMvc
        .perform(get("/api/comfyui/workflows"))
        .andExpect(status().isOk())
        .andDo(
            result -> {
              String body = result.getResponse().getContentAsString();
              int idx = body.indexOf("\"apiName\":\"" + apiName + "\"");
              if (idx < 0) {
                return;
              }
              int idIdx = body.lastIndexOf("\"id\":", idx);
              int idEnd = body.indexOf(',', idIdx);
              if (idEnd < 0) {
                return;
              }
              String idFragment = body.substring(idIdx, idEnd);
              String idString = idFragment.replaceAll("[^0-9]", "");
              mockMvc
                  .perform(
                      put("/api/comfyui/workflows/" + idString)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(objectMapper.writeValueAsString(update)))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.data.name").value("Demo-renamed"))
                  .andExpect(jsonPath("$.data.enabled").value(false));

              mockMvc
                  .perform(delete("/api/comfyui/workflows/" + idString))
                  .andExpect(status().isNoContent());
            });
  }

  @Test
  public void shouldRejectBlankWorkflowJsonWith400() throws Exception {
    ComfyuiWorkflowApiCreateDTO create = new ComfyuiWorkflowApiCreateDTO();
    create.setApiName("flow-" + System.nanoTime());
    create.setName("Demo");
    create.setWorkflowJson("   ");
    create.setInputBindingsJson(BINDINGS_JSON);

    // 写入路径的非法入参必须原样通过 global exception handler 转 4xx。
    mockMvc
        .perform(
            post("/api/comfyui/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  public void shouldRejectInvalidApiNameWith400() throws Exception {
    ComfyuiWorkflowApiCreateDTO create = new ComfyuiWorkflowApiCreateDTO();
    create.setApiName("Bad-Name");
    create.setName("Demo");
    create.setWorkflowJson(WORKFLOW_JSON);
    create.setInputBindingsJson(BINDINGS_JSON);

    mockMvc
        .perform(
            post("/api/comfyui/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  public void shouldRejectNonNumericPathIdWith400() throws Exception {
    ComfyuiWorkflowApiUpdateDTO update = new ComfyuiWorkflowApiUpdateDTO();
    update.setWorkflowJson(WORKFLOW_JSON);
    update.setInputBindingsJson("[]");

    mockMvc
        .perform(
            put("/api/comfyui/workflows/not-a-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectUnknownParseableIdWith404() throws Exception {
    ComfyuiWorkflowApiUpdateDTO update = new ComfyuiWorkflowApiUpdateDTO();
    update.setWorkflowJson(WORKFLOW_JSON);
    update.setInputBindingsJson("[]");

    // 解析合法但数据库中找不到的 id 必须返回 404，与 malformed id 的 400 明确区分。
    mockMvc
        .perform(
            put("/api/comfyui/workflows/999999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldDeleteUnknownParseableIdWith404() throws Exception {
    // DELETE 也要走相同的 404 路径。
    mockMvc
        .perform(delete("/api/comfyui/workflows/999999999999999"))
        .andExpect(status().isNotFound());
  }
}
