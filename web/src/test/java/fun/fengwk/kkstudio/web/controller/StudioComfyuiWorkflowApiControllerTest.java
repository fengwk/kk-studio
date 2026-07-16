package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link StudioComfyuiWorkflowApiController} 的端到端测试。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>CRUD 路由；
 *   <li>响应 DTO 中的 {@code id} 为十进制字符串形式；
 *   <li>路径参数非法（{@code not-a-number}）通过 global exception handler 转化为 400；
 *   <li>解析合法但数据库中找不到的 id → 404；
 *   <li>写入路径非法入参（空白 workflowJson / 非小写 apiName）通过 global exception handler 转化为 400。
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

    // 1) 创建：严格通过 ObjectMapper 解析响应并提取十进制字符串 id。
    MvcResult createResult =
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
            .andExpect(jsonPath("$.data.updateTime").exists())
            .andReturn();

    JsonNode dataNode =
        objectMapper.readTree(createResult.getResponse().getContentAsString()).get("data");
    assertNotNull(dataNode, "response must carry a data envelope");
    JsonNode idNode = dataNode.get("id");
    assertNotNull(idNode, "data.id must be present");
    assertTrue(idNode.isTextual(), "data.id must be a string, was: " + idNode.getNodeType());
    String idString = idNode.asText();
    assertTrue(
        idString.matches("\\d+"), "data.id must be a non-blank decimal string, was: " + idString);
    assertTrue(Long.parseLong(idString) > 0, "data.id must be positive, was: " + idString);

    try {
      // 2) 列表：刚创建的资源必须出现在分页结果中。
      mockMvc
          .perform(get("/api/comfyui/workflows").param("pageNumber", "1").param("pageSize", "100"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.results[?(@.id=='" + idString + "')]").exists());

      // 3) 更新：必须使用从创建响应里提取到的精确 id。
      ComfyuiWorkflowApiUpdateDTO update = new ComfyuiWorkflowApiUpdateDTO();
      update.setName("Demo-renamed");
      update.setWorkflowJson(WORKFLOW_JSON);
      update.setInputBindingsJson("[]");
      update.setDefaultSelector(null);
      update.setEnabled(false);

      mockMvc
          .perform(
              put("/api/comfyui/workflows/" + idString)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(update)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(idString))
          .andExpect(jsonPath("$.data.apiName").value(apiName))
          .andExpect(jsonPath("$.data.name").value("Demo-renamed"))
          .andExpect(jsonPath("$.data.enabled").value(false));
    } finally {
      // 4) 删除：精确 id，必须 204。
      mockMvc
          .perform(delete("/api/comfyui/workflows/" + idString))
          .andExpect(status().isNoContent());
    }
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
