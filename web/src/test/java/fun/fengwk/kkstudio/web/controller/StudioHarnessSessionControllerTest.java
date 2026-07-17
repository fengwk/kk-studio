package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.web.WebTestApplication;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** T15 全局 Harness Session/Run HTTP 契约测试。 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioHarnessSessionControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void resetState() {
    jdbc.update("delete from model_usage_record");
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
    jdbc.update(
        """
        merge into agent_definition (
            id, name, description, system_prompt, model_id, variant, config_json,
            gmt_create, gmt_modified, version
        ) key (id) values (
            1, 'default-assistant', 'Harness controller fixture.', 'system', 1, 'default',
            '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{}}',
            current_timestamp(), current_timestamp(), 0
        )
        """);
  }

  /** 创建、提交、Session/Entry/Run 查询均保持 bigint ID 的 JSON 字符串契约。 */
  @Test
  void createsAndQueriesGlobalHarnessSessionAndRun() throws Exception {
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentDefinitionId\":\"1\",\"title\":\"controller-root\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.agentDefinitionId").value("1"))
            .andExpect(jsonPath("$.data.title").value("controller-root"))
            .andReturn();
    JsonNode session = data(createResult);
    assertTrue(session.path("sessionId").isTextual());
    assertTrue(session.path("leafEntryId").isTextual());
    String sessionId = session.path("sessionId").asText();
    String snapshotEntryId = session.path("leafEntryId").asText();

    mockMvc
        .perform(get("/api/sessions/{id}", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.leafEntryId").value(snapshotEntryId));

    mockMvc
        .perform(get("/api/sessions/{id}/entries", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].sessionEntryId").value(snapshotEntryId))
        .andExpect(jsonPath("$.data[0].entryType").value("agent_snapshot"));

    MvcResult messageResult =
        mockMvc
            .perform(
                post("/api/sessions/{id}/messages", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"content\":\"hello\",\"expectedLeafEntryId\":\""
                            + snapshotEntryId
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.entryType").value("message"))
            .andReturn();
    JsonNode entry = data(messageResult);
    assertTrue(entry.path("sessionEntryId").isTextual());
    assertTrue(entry.path("runId").isTextual());
    String runId = entry.path("runId").asText();

    mockMvc
        .perform(get("/api/sessions/{id}/entries", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[1].runId").value(runId));

    mockMvc
        .perform(get("/api/sessions/{id}/runs", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].runId").value(runId))
        .andExpect(jsonPath("$.data[0].sessionId").value(sessionId));

    mockMvc
        .perform(get("/api/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.runId").value(runId))
        .andExpect(jsonPath("$.data.sessionId").value(sessionId));
  }

  /**
   * GET /api/sessions 只返回 parent_session_id 为空的根 Session，按 gmt_modified desc、id desc 排序；id
   * 仍为十进制字符串。
   */
  @Test
  void listsOnlyRootSessionsOrderedByRecentUpdate() throws Exception {
    long rootOld = 1001L;
    long rootMid = 1002L;
    long rootNew = 1003L;
    long child = 1099L;
    Instant base = Instant.parse("2026-07-17T00:00:00Z");

    insertRoot(rootOld, "root-old", Timestamp.from(base));
    insertRoot(rootMid, "root-mid", Timestamp.from(base.plusSeconds(60)));
    insertRoot(rootNew, "root-new", Timestamp.from(base.plusSeconds(120)));
    insertChild(child, rootOld, Timestamp.from(base.plusSeconds(180)));

    mockMvc
        .perform(get("/api/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[0].sessionId").value(Long.toString(rootNew)))
        .andExpect(jsonPath("$.data[0].title").value("root-new"))
        .andExpect(jsonPath("$.data[1].sessionId").value(Long.toString(rootMid)))
        .andExpect(jsonPath("$.data[1].title").value("root-mid"))
        .andExpect(jsonPath("$.data[2].sessionId").value(Long.toString(rootOld)))
        .andExpect(jsonPath("$.data[2].title").value("root-old"))
        .andExpect(jsonPath("$.data[?(@.sessionId == '" + child + "')]").doesNotExist());

    MvcResult result = mockMvc.perform(get("/api/sessions")).andExpect(status().isOk()).andReturn();
    JsonNode arr = data(result);
    assertTrue(arr.isArray());
    for (JsonNode node : arr) {
      assertTrue(node.path("sessionId").isTextual());
      assertTrue(node.path("sessionId").asText().matches("\\d+"));
      assertTrue(!node.has("parentSessionId") || node.path("parentSessionId").isNull());
      assertEquals(0, node.path("depth").asInt());
    }
  }

  /** 同 gmt_modified 时按 id desc 兜底排序。 */
  @Test
  void tiesOnGmtModifiedBreakByIdDescending() throws Exception {
    long rootA = 2001L;
    long rootB = 2002L;
    Instant sameInstant = Instant.parse("2026-07-17T01:00:00Z");
    insertRoot(rootA, "tie-a", Timestamp.from(sameInstant));
    insertRoot(rootB, "tie-b", Timestamp.from(sameInstant));

    mockMvc
        .perform(get("/api/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(Long.toString(rootB)))
        .andExpect(jsonPath("$.data[1].sessionId").value(Long.toString(rootA)));
  }

  private void insertRoot(long id, String title, Timestamp gmtModified) {
    jdbc.update(
        "insert into harness_session (id, agent_definition_id, title, leaf_entry_id,"
            + " active_run_id, parent_session_id, root_session_id, parent_invocation_id, depth,"
            + " yolo_enabled, gmt_create, gmt_modified, version) values (?, 1, ?, null, null,"
            + " null, ?, null, 0, false, ?, ?, 0)",
        id,
        title,
        id,
        gmtModified,
        gmtModified);
  }

  private void insertChild(long id, long parentId, Timestamp gmtModified) {
    jdbc.update(
        "insert into harness_session (id, agent_definition_id, title, leaf_entry_id,"
            + " active_run_id, parent_session_id, root_session_id, parent_invocation_id, depth,"
            + " yolo_enabled, gmt_create, gmt_modified, version) values (?, 1, 'child-of-"
            + parentId
            + "', null, null, ?, ?, null, 1, false, ?, ?, 0)",
        id,
        parentId,
        parentId,
        gmtModified,
        gmtModified);
  }

  /** 非法 bigint ID 是请求错误，未知的 Session、Run、AgentDefinition 是资源不存在。 */
  @Test
  void distinguishesMalformedAndMissingHarnessResources() throws Exception {
    mockMvc.perform(get("/api/sessions/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/runs/{id}", "0")).andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/sessions/{id}", "9999999999")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/sessions/{id}/runs", "9999999999")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/runs/{id}", "9999999999")).andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentDefinitionId\":\"9999999999\",\"title\":\"missing\"}"))
        .andExpect(status().isNotFound());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
