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

import fun.fengwk.kkstudio.core.harness.usage.store.PostgresqlModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.math.BigDecimal;
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
  void exposesThreadSessionAndModelPathsWithStringLargeIds() throws Exception {
    insertThreadPath();
    recordStore.insert(
        new ModelUsageRecord(
            recordIds.newModelUsageRecordId(), LARGE_ID, LARGE_ID, LARGE_ID, draft(LARGE_ID), NOW));

    for (String scope : List.of("threads", "sessions", "models")) {
      String scopeType = scope.substring(0, scope.length() - 1);
      mockMvc
          .perform(get("/api/usage/{scope}/{id}", scope, Long.toString(LARGE_ID)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.scopeType").value(scopeType))
          .andExpect(jsonPath("$.data.scopeId").value(Long.toString(LARGE_ID)))
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
  }

  /** 孤立账本不能替代 Thread 元数据：没有 Thread 行时查询直接 404。 */
  @Test
  void rejectsUnknownThreadEvenWhenLedgerRowsExist() throws Exception {
    // final schema FKs prevent orphan ledger rows; unknown Thread remains 404 without inserts.
    mockMvc
        .perform(get("/api/usage/threads/{id}", Long.toString(LARGE_ID)))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsInvalidIdsOnAllPaths() throws Exception {
    for (String scope : List.of("threads", "sessions", "models")) {
      for (String invalid : List.of("abc", "0", "-1", "9223372036854775808")) {
        mockMvc
            .perform(get("/api/usage/{scope}/{id}", scope, invalid))
            .andExpect(status().isBadRequest());
      }
    }
  }

  private void insertThreadPath() {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              connection.setAutoCommit(false);
              try (var st = connection.createStatement()) {
                st.execute(
                    "insert into harness_session (id, title, main_thread_id, created_at, updated_at) values ("
                        + LARGE_ID
                        + ", 'usage-test', "
                        + LARGE_ID
                        + ", now(), now())");
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
                    "insert into harness_thread (id, session_id, head_entry_id, input_sequence, runnable,"
                        + " execution_epoch, created_at, updated_at) values ("
                        + LARGE_ID
                        + ", "
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

  private static ModelUsageDraft draft(long modelResourceId) {
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
        1L,
        modelResourceId,
        ProviderType.OPENAI,
        "model",
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
