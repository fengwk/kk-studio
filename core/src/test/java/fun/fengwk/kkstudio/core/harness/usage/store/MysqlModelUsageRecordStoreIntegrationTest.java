package fun.fengwk.kkstudio.core.harness.usage.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.usage.store.mapper.ModelUsageRecordMapper;
import fun.fengwk.kkstudio.core.harness.usage.store.model.ModelUsageRecordDO;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

/**
 * 真实 H2 schema 上的端到端覆盖：独立 ID namespace、扁平 ModelUsageDraft 全字段 round-trip、Assistant Entry unique
 * 键、id asc 排序、并发受影响 !=1 行为、schema 列/索引约束。
 */
@SpringBootTest(classes = CoreTestApplication.class)
class MysqlModelUsageRecordStoreIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  @Autowired private MysqlModelUsageRecordStore store;
  @Autowired private ModelUsageRecordIdGenerator idGenerator;
  @Autowired private ModelUsageRecordMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from model_usage_record");
  }

  /** 每个账本 id 必须来自独立 namespace。 */
  @Test
  void generatesIdsFromIndependentNamespace() {
    long idA = idGenerator.newModelUsageRecordId();
    long idB = idGenerator.newModelUsageRecordId();
    assertTrue(idA > 0);
    assertTrue(idB > 0);
    assertNotEquals(idA, idB);
  }

  /** insert/findByAssistantEntryId 完整 round-trip 所有字段。 */
  @Test
  void insertAndFindByAssistantEntryIdRoundTripsAllFields() {
    ModelUsageDraft draft = draftWithAffinity();
    ModelUsageRecord original =
        new ModelUsageRecord(idGenerator.newModelUsageRecordId(), 11L, 21L, 31L, draft, NOW);

    assertEquals(1, store.insert(original));

    Optional<ModelUsageRecord> loaded = store.findByAssistantEntryId(31L);
    assertTrue(loaded.isPresent());
    ModelUsageRecord found = loaded.get();
    assertEquals(original.id(), found.id());
    assertEquals(11L, found.sessionId());
    assertEquals(21L, found.threadId());
    assertEquals(31L, found.assistantEntryId());
    assertEquals(NOW, found.createdAt());

    ModelUsageDraft loadedDraft = found.draft();
    assertEquals(101L, loadedDraft.providerResourceId());
    assertEquals(202L, loadedDraft.modelResourceId());
    assertEquals(ProviderType.GOOGLE, loadedDraft.providerType());
    assertEquals("gemini-x", loadedDraft.providerModelId());
    assertEquals(PromptCacheMode.AFFINITY, loadedDraft.promptCacheMode());
    assertEquals(PromptCacheRetention.LONG, loadedDraft.promptCacheRetention());
    assertTrue(loadedDraft.cacheEligible());
    assertEquals("aff-1", loadedDraft.cacheAffinityKey());
    assertEquals(ProviderStopReason.COMPLETED, loadedDraft.stopReason());
    assertEquals(draft.usage(), loadedDraft.usage());
    assertEquals(draft.cost(), loadedDraft.cost());
    assertEquals(draft.pricing(), loadedDraft.pricing());
    assertEquals("req-1", loadedDraft.requestId());
    assertEquals("tier-reported", loadedDraft.reportedServiceTier());
    assertEquals("{\"x\":1}", loadedDraft.rawUsageJson());
  }

  /** cacheAffinityKey 为 null 的 record 也必须 round-trip 完整。 */
  @Test
  void insertAndFindSupportsNullCacheAffinityKey() {
    ModelUsageDraft draft = draftWithoutCache();
    ModelUsageRecord original =
        new ModelUsageRecord(idGenerator.newModelUsageRecordId(), 11L, 21L, 41L, draft, NOW);
    assertEquals(1, store.insert(original));

    ModelUsageRecord found = store.findByAssistantEntryId(41L).orElseThrow();
    assertEquals(PromptCacheMode.UNSUPPORTED, found.draft().promptCacheMode());
    assertEquals(PromptCacheRetention.NONE, found.draft().promptCacheRetention());
    assertFalse(found.draft().cacheEligible());
    assertEquals(null, found.draft().cacheAffinityKey());
  }

  /** listByThreadId 按 id asc 排序，且仅返回该 Thread 的记录。 */
  @Test
  void listByThreadIdOrdersByIdAscAndIsolatesByThread() {
    ModelUsageRecord a =
        insert(idGenerator.newModelUsageRecordId(), 11L, 21L, 31L, draftWithoutCache(), NOW);
    ModelUsageRecord b =
        insert(idGenerator.newModelUsageRecordId(), 11L, 21L, 32L, draftWithoutCache(), NOW);
    ModelUsageRecord otherThread =
        insert(idGenerator.newModelUsageRecordId(), 11L, 22L, 33L, draftWithoutCache(), NOW);

    List<Long> byThread = store.listByThreadId(21L).stream().map(ModelUsageRecord::id).toList();
    assertEquals(List.of(a.id(), b.id()), byThread);
    assertEquals(
        List.of(otherThread.id()),
        store.listByThreadId(22L).stream().map(ModelUsageRecord::id).toList());
    assertTrue(store.listByThreadId(99L).isEmpty());
  }

  /** Session 与 Model scope 查询均按 id asc，并且不会泄漏其它 scope 的记录。 */
  @Test
  void listBySessionAndModelOrdersByIdAscAndIsolatesScopes() {
    ModelUsageRecord first =
        insert(idGenerator.newModelUsageRecordId(), 11L, 21L, 31L, draftWithoutCache(201L), NOW);
    ModelUsageRecord second =
        insert(idGenerator.newModelUsageRecordId(), 11L, 22L, 32L, draftWithoutCache(202L), NOW);
    ModelUsageRecord otherSession =
        insert(idGenerator.newModelUsageRecordId(), 12L, 23L, 33L, draftWithoutCache(201L), NOW);

    assertEquals(
        List.of(first.id(), second.id()),
        store.listBySessionId(11L).stream().map(ModelUsageRecord::id).toList());
    assertEquals(
        List.of(otherSession.id()),
        store.listBySessionId(12L).stream().map(ModelUsageRecord::id).toList());
    assertEquals(
        List.of(first.id(), otherSession.id()),
        store.listByModelResourceId(201L).stream().map(ModelUsageRecord::id).toList());
    assertEquals(
        List.of(second.id()),
        store.listByModelResourceId(202L).stream().map(ModelUsageRecord::id).toList());
    assertTrue(store.listBySessionId(99L).isEmpty());
    assertTrue(store.listByModelResourceId(999L).isEmpty());
  }

  /** unique(assistant_entry_id) 必须拦截重复的 Assistant Entry 写入。 */
  @Test
  void duplicateAssistantEntryIdIsRejected() {
    long id = idGenerator.newModelUsageRecordId();
    store.insert(new ModelUsageRecord(id, 11L, 21L, 31L, draftWithoutCache(), NOW));
    long other = idGenerator.newModelUsageRecordId();
    assertThrows(
        DataIntegrityViolationException.class,
        () -> store.insert(new ModelUsageRecord(other, 11L, 21L, 31L, draftWithoutCache(), NOW)));
  }

  /** affected != 1 必须以 ConcurrentModificationException 上抛（用假 mapper 替换注入）。 */
  @Test
  void concurrentModificationWhenAffectedNotOne() {
    ModelUsageRecordMapper stubMapper =
        new ModelUsageRecordMapper() {
          @Override
          public int insert(ModelUsageRecordDO row) {
            return 0;
          }

          @Override
          public ModelUsageRecordDO findByAssistantEntryId(long assistantEntryId) {
            return null;
          }

          @Override
          public List<ModelUsageRecordDO> listByThreadId(long threadId) {
            return List.of();
          }

          @Override
          public List<ModelUsageRecordDO> listBySessionId(long sessionId) {
            return List.of();
          }

          @Override
          public List<ModelUsageRecordDO> listByModelResourceId(long modelResourceId) {
            return List.of();
          }
        };
    MysqlModelUsageRecordStore localStore = new MysqlModelUsageRecordStore(stubMapper);
    ModelUsageRecord record =
        new ModelUsageRecord(
            idGenerator.newModelUsageRecordId(), 11L, 21L, 31L, draftWithoutCache(), NOW);
    assertThrows(ConcurrentModificationException.class, () -> localStore.insert(record));
  }

  /** schema 必须无 workspace / tenant 列。 */
  @Test
  void schemaDoesNotCarryWorkspaceOrTenantColumns() {
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns"
                + " where lower(table_name) = 'model_usage_record'",
            String.class);
    String joined = String.join(",", columns).toLowerCase();
    assertFalse(joined.contains("workspace"), joined);
    assertFalse(joined.contains("tenant"), joined);
    assertTrue(joined.contains("assistant_entry_id"), joined);
    assertTrue(joined.contains("thread_id"), joined);
    assertTrue(joined.contains("session_id"), joined);
    assertTrue(joined.contains("model_resource_id"), joined);
    assertTrue(joined.contains("raw_usage_json"), joined);
    assertTrue(joined.contains("cost_total"), joined);
  }

  /** 期望的索引在 schema 上必须存在。 */
  @Test
  void schemaHasRequiredIndexes() {
    List<String> indexes =
        jdbc.queryForList(
            "select index_name from information_schema.indexes"
                + " where lower(table_name) = 'model_usage_record'",
            String.class);
    assertTrue(
        indexes.stream().anyMatch("idx_model_usage_record_thread"::equalsIgnoreCase),
        "missing thread index: " + indexes);
    assertTrue(
        indexes.stream().anyMatch("idx_model_usage_record_session"::equalsIgnoreCase),
        "missing session index: " + indexes);
    assertTrue(
        indexes.stream().anyMatch("idx_model_usage_record_model"::equalsIgnoreCase),
        "missing model index: " + indexes);
  }

  /** findByAssistantEntryId 缺失时返回 Optional.empty()，不抛异常。 */
  @Test
  void findByAssistantEntryIdReturnsEmptyForMissing() {
    assertFalse(store.findByAssistantEntryId(999_999L).isPresent());
  }

  private ModelUsageRecord insert(
      long id,
      long sessionId,
      long threadId,
      long assistantEntryId,
      ModelUsageDraft draft,
      Instant createdAt) {
    ModelUsageRecord record =
        new ModelUsageRecord(id, sessionId, threadId, assistantEntryId, draft, createdAt);
    store.insert(record);
    return record;
  }

  private static ModelUsageDraft draftWithAffinity() {
    ModelUsage usage = new ModelUsage(1, 2, 3, 4, 5, 6, 21);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE.setScale(12),
            "v1",
            new BigDecimal("0.000001000000"),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12));
    ModelCost cost = ModelCost.calculate(pricing, usage);
    return new ModelUsageDraft(
        101L,
        202L,
        ProviderType.GOOGLE,
        "gemini-x",
        PromptCacheMode.AFFINITY,
        PromptCacheRetention.LONG,
        true,
        "aff-1",
        ProviderStopReason.COMPLETED,
        usage,
        cost,
        pricing,
        "req-1",
        "tier-reported",
        "{\"x\":1}");
  }

  private static ModelUsageDraft draftWithoutCache() {
    return draftWithoutCache(2L);
  }

  private static ModelUsageDraft draftWithoutCache(long modelResourceId) {
    ModelUsage usage = new ModelUsage(1, 0, 0, 0, 0, 0, 1);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE.setScale(12),
            "v1",
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12));
    ModelCost cost = ModelCost.calculate(pricing, usage);
    return new ModelUsageDraft(
        1L,
        modelResourceId,
        ProviderType.OPENAI,
        "model",
        PromptCacheMode.UNSUPPORTED,
        PromptCacheRetention.NONE,
        false,
        null,
        ProviderStopReason.COMPLETED,
        usage,
        cost,
        pricing,
        null,
        null,
        "{}");
  }
}
