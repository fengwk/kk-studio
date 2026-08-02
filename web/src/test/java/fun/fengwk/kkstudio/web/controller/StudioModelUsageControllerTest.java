package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fun.fengwk.kkstudio.core.ai.runtime.usage.store.PostgresqlModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@AutoConfigureMockMvc
class StudioModelUsageControllerTest extends WebPostgresTestSupport {

  private static final long LARGE_ID = 9_007_199_254_740_993L;
  private static final long ROOT_ENTRY_ID = LARGE_ID - 1;
  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private PostgresqlModelUsageRecordStore recordStore;
  @Autowired private ModelUsageRecordIdGenerator recordIds;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_model_usage");
    jdbc.update("delete from harness_thread where id = ?", LARGE_ID);
    jdbc.update("delete from harness_entry where session_id = ?", LARGE_ID);
    jdbc.update("delete from harness_session where id = ?", LARGE_ID);
  }

  @Test
  void exposesSessionAndNameBasedModelPaths() throws Exception {
    insertThreadPath();
    recordStore.insert(
        new ModelUsageRecord(
            recordIds.newModelUsageRecordId(),
            LARGE_ID,
            LARGE_ID,
            LARGE_ID,
            draft("org/model/v2"),
            NOW));

    assertSummary("/api/ai/runtime/usage/sessions/" + LARGE_ID, "session", Long.toString(LARGE_ID));
    assertSummary(
        get("/api/ai/runtime/usage/models")
            .param("providerName", "openai")
            .param("modelName", "org/model/v2"),
        "model",
        "openai/org/model/v2");
  }

  @Test
  void rejectsInvalidIdsAndModelReferences() throws Exception {
    for (String invalid : List.of("abc", "0", "-1", "9223372036854775808")) {
      mockMvc
          .perform(get("/api/ai/runtime/usage/sessions/{sessionId}", invalid))
          .andExpect(status().isBadRequest());
    }
    for (String invalid : List.of("", " model ", "\u2003model\u2003")) {
      mockMvc
          .perform(
              get("/api/ai/runtime/usage/models")
                  .param("providerName", "openai")
                  .param("modelName", invalid))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(get("/api/ai/runtime/usage/models").param("providerName", "openai"))
        .andExpect(status().isBadRequest());
  }

  private void assertSummary(String path, String scopeType, String expectedScopeId)
      throws Exception {
    assertSummary(get(URI.create(path)), scopeType, expectedScopeId);
  }

  private void assertSummary(
      MockHttpServletRequestBuilder request, String scopeType, String expectedScopeId)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.scopeType").value(scopeType))
        .andExpect(jsonPath("$.data.scopeId").value(expectedScopeId))
        .andExpect(jsonPath("$.data.recordCount").value(1))
        .andExpect(jsonPath("$.data.inputTokens").value(10))
        .andExpect(jsonPath("$.data.cacheReadTokens").value(30))
        .andExpect(jsonPath("$.data.cacheEligibleRecordCount").value(1))
        .andExpect(jsonPath("$.data.cacheHitRecordCount").value(1))
        .andExpect(jsonPath("$.data.cacheHitRatio").value(1.000000))
        .andExpect(jsonPath("$.data.tokenReadRatio").value(0.750000))
        .andExpect(jsonPath("$.data.unamortizedCacheWriteTokens").value(0))
        .andExpect(jsonPath("$.data.costs[0].currency").value("USD"));
  }

  private void insertThreadPath() {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              connection.setAutoCommit(false);
              try (var st = connection.createStatement()) {
                st.execute(
                    "insert into harness_session (id, title, created_at) values ("
                        + LARGE_ID
                        + ", 'usage-test', now())");
                st.execute(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
                        + " values ("
                        + ROOT_ENTRY_ID
                        + ", "
                        + LARGE_ID
                        + ", null, 'ROOT', '{}'::jsonb, now())");
                st.execute(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
                        + " values ("
                        + LARGE_ID
                        + ", "
                        + LARGE_ID
                        + ", "
                        + ROOT_ENTRY_ID
                        + ", 'MESSAGE', cast('"
                        + assistantPayloadJson().replace("'", "''")
                        + "' as jsonb), now())");
                st.execute(
                    "insert into harness_thread (id, head_entry_id, input_sequence, runnable,"
                        + " execution_epoch, created_at, updated_at) values ("
                        + LARGE_ID
                        + ", "
                        + LARGE_ID
                        + ", 0, false, 0, now(), now())");
              }
              connection.commit();
              connection.setAutoCommit(true);
              return null;
            });
  }

  private static String assistantPayloadJson() {
    return "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\","
        + "\"text\":\"ok\"}]},\"assistantMetadata\":{\"stopReason\":\"COMPLETED\","
        + "\"usage\":{\"inputTokens\":10,\"outputTokens\":0,\"cacheReadTokens\":30,"
        + "\"cacheWriteTokens\":5,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,"
        + "\"providerTotalTokens\":45},\"cost\":{\"currency\":\"USD\",\"input\":\"0\","
        + "\"output\":\"0\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\","
        + "\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"0\"}}}";
  }

  private static ModelUsageDraft draft(String modelName) {
    ModelUsage usage = new ModelUsage(10, 0, 30, 5, 0, 0, 45);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier",
            "default",
            BigDecimal.ONE.setScale(12),
            "v1",
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12));
    return new ModelUsageDraft(
        "openai",
        modelName,
        ProviderType.OPENAI,
        PromptCacheMode.AUTOMATIC,
        PromptCacheRetention.NONE,
        true,
        null,
        ProviderStopReason.COMPLETED,
        usage,
        ModelCost.calculate(pricing, usage),
        pricing,
        null,
        null,
        "{}");
  }
}
