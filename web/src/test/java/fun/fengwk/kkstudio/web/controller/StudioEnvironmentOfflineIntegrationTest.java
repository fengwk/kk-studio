package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.List;

/**
 * 意图：验证完整的 Web 层 + PostgreSQL 环境管理离线场景集成流： 包含环境生命周期、Skill 来源 CRUD、持久化清单查询、异步管理操作提交（202 PENDING）、
 * 活跃操作排他冲突校验（409 Duplicate）、默认 PATH 来源 install 语义拦截（400 Validation）、 同步取消操作（200 CANCELLED）、严格 UUID
 * 校验、以及完整的 try/finally 级联清理。
 */
@AutoConfigureMockMvc
class StudioEnvironmentOfflineIntegrationTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;

  @Test
  void fullOfflineEnvironmentSkillOperationsFlow() throws Exception {
    String envId = null;
    String customSourceId = null;

    try {
      // 1. 创建 Environment，验证 201 Created 与 no-store 凭据缓存控制
      MvcResult envCreateResult =
          mockMvc
              .perform(
                  post("/api/harness/environments")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"name\":\"integration-test-env\"}"))
              .andExpect(status().isCreated())
              .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
              .andExpect(jsonPath("$.data.id").isNotEmpty())
              .andExpect(jsonPath("$.data.name").value("integration-test-env"))
              .andReturn();

      envId = JsonPath.read(envCreateResult.getResponse().getContentAsString(), "$.data.id");
      assertNotNull(envId);

      // 2. 查询环境详情，Card 投影中绝无 registrationToken
      mockMvc
          .perform(get("/api/harness/environments/" + envId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(envId))
          .andExpect(jsonPath("$.data.registrationToken").doesNotExist());

      // 3. 注册 Git Skill 来源（使用 example.invalid 域名规范）
      MvcResult sourceCreateResult =
          mockMvc
              .perform(
                  post("/api/harness/environments/" + envId + "/skill-sources")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"type\":\"git\",\"gitUrl\":\"https://example.invalid/test-skills.git\"}"))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.data.sourceId").isNotEmpty())
              .andExpect(jsonPath("$.data.type").value("git"))
              .andExpect(jsonPath("$.data.version").value("0"))
              .andExpect(jsonPath("$.data.status").value("UNAPPLIED"))
              .andReturn();

      customSourceId =
          JsonPath.read(sourceCreateResult.getResponse().getContentAsString(), "$.data.sourceId");
      assertNotNull(customSourceId);

      // 4. 读取来源列表（包含默认来源和新注册来源共 2 个），记录默认 PATH 来源 ID
      MvcResult listSourcesResult =
          mockMvc
              .perform(get("/api/harness/environments/" + envId + "/skill-sources"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data", hasSize(2)))
              .andReturn();

      List<String> defaultSources =
          JsonPath.read(
              listSourcesResult.getResponse().getContentAsString(),
              "$.data[?(@.defaultSource == true)].sourceId");
      String defaultSourceId = defaultSources.getFirst();

      // 5. 验证 PATH 来源不允许提交 install，抛出语义校验错误 400
      mockMvc
          .perform(
              post("/api/harness/environments/"
                      + envId
                      + "/skill-sources/"
                      + defaultSourceId
                      + "/install")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"timeoutMillis\":5000}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("validation"));

      // 6. 更新 Git 来源配置（CAS，PUT 全量替换需包含 type 与必填字段）
      mockMvc
          .perform(
              put("/api/harness/environments/" + envId + "/skill-sources/" + customSourceId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"type\":\"git\",\"gitUrl\":\"https://example.invalid/test-skills.git\",\"gitRef\":\"main\",\"expectedVersion\":\"0\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.sourceId").value(customSourceId))
          .andExpect(jsonPath("$.data.version").value("1"));

      // 7. 查询持久清单（头信息）与 Skill 列表
      mockMvc
          .perform(get("/api/harness/environments/" + envId + "/inventory"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.environmentId").value(envId))
          .andExpect(jsonPath("$.data.sourceSetVersion").isNotEmpty());

      mockMvc
          .perform(get("/api/harness/environments/" + envId + "/inventory/skills"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(0)));

      // 8. 提交异步 Install 操作（返回 202 Accepted，状态为 PENDING）
      MvcResult opResult =
          mockMvc
              .perform(
                  post("/api/harness/environments/"
                          + envId
                          + "/skill-sources/"
                          + customSourceId
                          + "/install")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"timeoutMillis\":5000}"))
              .andExpect(status().isAccepted())
              .andExpect(jsonPath("$.data.id").isNotEmpty())
              .andExpect(jsonPath("$.data.operationType").value("SKILL_INSTALL"))
              .andExpect(jsonPath("$.data.status").value("PENDING"))
              .andReturn();

      String opId = JsonPath.read(opResult.getResponse().getContentAsString(), "$.data.id");
      assertNotNull(opId);

      // 9. 测试并发冲突：在已有 PENDING 操作未终结时，提交同来源同环境的第二个操作必须返回 409 Conflict
      mockMvc
          .perform(
              post("/api/harness/environments/"
                      + envId
                      + "/skill-sources/"
                      + customSourceId
                      + "/install")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"timeoutMillis\":5000}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.status").value(409))
          .andExpect(jsonPath("$.code").value("duplicate"));

      // 10. 查询单个操作与操作列表，验证状态仍为 PENDING
      mockMvc
          .perform(get("/api/harness/environments/" + envId + "/operations/" + opId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(opId))
          .andExpect(jsonPath("$.data.status").value("PENDING"))
          .andExpect(jsonPath("$.data.operationType").value("SKILL_INSTALL"));

      mockMvc
          .perform(get("/api/harness/environments/" + envId + "/operations?limit=10"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].id").value(opId));

      // 11. 同步取消操作，返回 200 OK 且状态变为 CANCELLED
      mockMvc
          .perform(post("/api/harness/environments/" + envId + "/operations/" + opId + "/cancel"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(opId))
          .andExpect(jsonPath("$.data.status").value("CANCELLED"));

      // 12. 严格 UUID 校验：大写或非法 UUID 格式返回 400，且不回显非法输入
      String malformedSourceId = "22222222-2222-2222-2222-22222222222A";
      mockMvc
          .perform(
              get("/api/harness/environments/" + envId + "/skill-sources/" + malformedSourceId))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("validation"))
          .andExpect(
              result ->
                  assertFalse(
                      result.getResponse().getContentAsString().contains(malformedSourceId)));

      // 13. 在操作已终结（CANCELLED）后，删除自定义来源（CAS）
      String deletedCustomSourceId = customSourceId;
      mockMvc
          .perform(
              delete("/api/harness/environments/" + envId + "/skill-sources/" + customSourceId)
                  .param("expectedVersion", "1"))
          .andExpect(status().isNoContent());
      customSourceId = null; // 标记已删除

      // 确认来源已删除（404）
      mockMvc
          .perform(
              get("/api/harness/environments/" + envId + "/skill-sources/" + deletedCustomSourceId))
          .andExpect(status().isNotFound());

    } finally {
      // 级联清理：若失败提前退出，安全清理自定义来源与环境
      if (envId != null && customSourceId != null) {
        try {
          MvcResult srcResult =
              mockMvc
                  .perform(
                      get(
                          "/api/harness/environments/"
                              + envId
                              + "/skill-sources/"
                              + customSourceId))
                  .andReturn();
          if (srcResult.getResponse().getStatus() == 200) {
            String ver =
                JsonPath.read(srcResult.getResponse().getContentAsString(), "$.data.version");
            mockMvc.perform(
                delete("/api/harness/environments/" + envId + "/skill-sources/" + customSourceId)
                    .param("expectedVersion", ver));
          }
        } catch (Exception ignored) {
        }
      }
      if (envId != null) {
        try {
          MvcResult envResult =
              mockMvc.perform(get("/api/harness/environments/" + envId)).andReturn();
          if (envResult.getResponse().getStatus() == 200) {
            String ver =
                JsonPath.read(envResult.getResponse().getContentAsString(), "$.data.version");
            mockMvc.perform(
                delete("/api/harness/environments/" + envId).param("expectedVersion", ver));
          }
        } catch (Exception ignored) {
        }
      }
    }
  }
}
