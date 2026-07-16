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
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentUpdateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link StudioToolEnvironmentController} end-to-end tests.
 *
 * <p>验证：
 *
 * <ul>
 *   <li>CRUD 路由；
 *   <li>响应 DTO 的 {@code id} 为十进制字符串形式；
 *   <li>路径参数非法（{@code not-a-number}）通过 global exception handler 转化为 400；
 *   <li>解析合法但数据库中找不到的 id → 404；
 *   <li>写入路径非法入参（blank name）通过 global exception handler 转化为 400；
 *   <li>REST create/update 不接受 capability/lastSeen 字段，写入路径因此直接丢弃而不是回显；
 *   <li>被 {@code tool_invocation} 引用的 Environment → 409。
 * </ul>
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioToolEnvironmentControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ToolEnvironmentRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  public void shouldCreateListUpdateAndDeleteEnvironment() throws Exception {
    String name = "env-" + System.nanoTime();

    ToolEnvironmentCreateDTO create = new ToolEnvironmentCreateDTO();
    create.setName(name);
    create.setDescription("desc");

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/environments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.description").value("desc"))
            .andExpect(jsonPath("$.data.capabilitiesJson").value("{\"tools\":[]}"))
            .andExpect(jsonPath("$.data.lastSeenAt").doesNotExist())
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
    assertTrue(idString.matches("\\d+"), "data.id must be unsigned decimal, was: " + idString);
    assertTrue(Long.parseLong(idString) > 0, "data.id must be positive, was: " + idString);

    try {
      // 1) 列表：刚创建的 Environment 必须出现在分页结果中。
      mockMvc
          .perform(get("/api/environments").param("pageNumber", "1").param("pageSize", "100"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.results[?(@.id=='" + idString + "')]").exists());

      // 2) 更新：必须使用从创建响应里提取到的精确 id。
      ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
      update.setName(name + "-renamed");
      update.setDescription("renamed");
      mockMvc
          .perform(
              put("/api/environments/" + idString)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(update)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(idString))
          .andExpect(jsonPath("$.data.name").value(name + "-renamed"))
          .andExpect(jsonPath("$.data.description").value("renamed"))
          .andExpect(jsonPath("$.data.capabilitiesJson").value("{\"tools\":[]}"));
    } finally {
      // 3) 删除：精确 id，必须 204。
      mockMvc.perform(delete("/api/environments/" + idString)).andExpect(status().isNoContent());
    }
  }

  @Test
  public void shouldRejectBlankNameWith400() throws Exception {
    ToolEnvironmentCreateDTO create = new ToolEnvironmentCreateDTO();
    create.setName("   ");
    mockMvc
        .perform(
            post("/api/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectDuplicateNameWith400() throws Exception {
    String name = "env-dup-" + System.nanoTime();
    ToolEnvironmentCreateDTO first = new ToolEnvironmentCreateDTO();
    first.setName(name);
    MvcResult firstResult =
        mockMvc
            .perform(
                post("/api/environments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(first)))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        objectMapper
            .readTree(firstResult.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    try {
      ToolEnvironmentCreateDTO conflict = new ToolEnvironmentCreateDTO();
      conflict.setName(name);
      mockMvc
          .perform(
              post("/api/environments")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(conflict)))
          .andExpect(status().isBadRequest());
    } finally {
      mockMvc.perform(delete("/api/environments/" + id)).andExpect(status().isNoContent());
    }
  }

  @Test
  public void shouldRejectMalformedPathIdWith400() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/not-a-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectZeroPathIdWith400() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectNegativePathIdWith400() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectPlusSignedPathIdWith400() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/+1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectOverflowPathIdWith400() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/99999999999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectUnknownParseableIdWith404() throws Exception {
    ToolEnvironmentUpdateDTO update = new ToolEnvironmentUpdateDTO();
    update.setName("env-x");
    mockMvc
        .perform(
            put("/api/environments/999999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldDeleteUnknownParseableIdWith404() throws Exception {
    mockMvc.perform(delete("/api/environments/999999999999999")).andExpect(status().isNotFound());
  }

  @Test
  public void shouldRejectDeleteReferencedEnvironmentWith409() throws Exception {
    String name = "env-ref-" + System.nanoTime();
    ToolEnvironmentCreateDTO create = new ToolEnvironmentCreateDTO();
    create.setName(name);
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/environments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    long envId = Long.parseLong(id);
    long invocationId = 700_000_000_000_000_000L + envId;
    jdbcTemplate.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id, "
            + "tool_name, tool_version, target_type, environment_id, arguments_json, status, "
            + "permission_action, deadline_at) "
            + "values (?, 1, 1, 1, ?, ?, ?, 'ENVIRONMENT', ?, '{}', 'QUEUED', 'ALLOW', "
            + "current_timestamp(3))",
        invocationId,
        "call-" + envId,
        "shell",
        "1",
        envId);
    try {
      mockMvc.perform(delete("/api/environments/" + id)).andExpect(status().isConflict());
    } finally {
      jdbcTemplate.update("delete from tool_invocation where id = ?", invocationId);
      mockMvc.perform(delete("/api/environments/" + id)).andExpect(status().isNoContent());
    }
  }

  @Test
  public void shouldIgnoreCapabilitiesAndLastSeenInCreateAndUpdatePayload() throws Exception {
    String name = "env-ignore-" + System.nanoTime();
    String payload =
        "{\"name\":\""
            + name
            + "\",\"description\":\"d\","
            + "\"capabilitiesJson\":\"{\\\"tools\\\":[{\\\"name\\\":\\\"rogue\\\"}]}\","
            + "\"lastSeenAt\":\"2099-01-01T00:00:00\","
            + "\"version\":42}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/environments").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.capabilitiesJson").value("{\"tools\":[]}"))
            .andExpect(jsonPath("$.data.lastSeenAt").doesNotExist())
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn();
    String id =
        objectMapper
            .readTree(result.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    try {
      String updatePayload =
          "{\"name\":\""
              + name
              + "\",\"description\":\"d\","
              + "\"capabilitiesJson\":\"{\\\"tools\\\":[{\\\"name\\\":\\\"rogue\\\"}]}\","
              + "\"lastSeenAt\":\"2099-01-01T00:00:00\"}";
      mockMvc
          .perform(
              put("/api/environments/" + id)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(updatePayload))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.capabilitiesJson").value("{\"tools\":[]}"))
          .andExpect(jsonPath("$.data.lastSeenAt").doesNotExist());
    } finally {
      mockMvc.perform(delete("/api/environments/" + id)).andExpect(status().isNoContent());
    }
  }
}
