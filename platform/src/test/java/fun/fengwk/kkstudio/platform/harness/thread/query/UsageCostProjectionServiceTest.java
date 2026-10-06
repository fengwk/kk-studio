package fun.fengwk.kkstudio.platform.harness.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 读取时费用投影契约：按每个 ASSISTANT 结果<b>祖先</b>记录的真实型号取<b>当前</b> catalog 价格并现算，且整次投影只读。
 *
 * <p>测试固定三件容易被后续实现悄悄弄错的事实：模型中途切换时逐条按各自当时的型号计价；无法定价（模型已删除或定义损坏）绝不伪装成 0
 * 费用；每个出现过的模型在一次投影里至多查一次 catalog。
 */
class UsageCostProjectionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);
  private static final UUID ROOT_ID = new UUID(0L, 2L);
  private static final UUID TURN_A_ID = new UUID(0L, 3L);
  private static final UUID USER_A_ID = new UUID(0L, 4L);
  private static final UUID ASSISTANT_A_ID = new UUID(0L, 5L);
  private static final UUID TURN_B_ID = new UUID(0L, 6L);
  private static final UUID USER_B_ID = new UUID(0L, 7L);
  private static final UUID ASSISTANT_B_ID = new UUID(0L, 8L);

  private final AgentModelRepository modelRepository = mock(AgentModelRepository.class);
  private final AgentModelRuntimeConfigParser configParser =
      new AgentModelRuntimeConfigParser(new ObjectMapper());

  private final UsageCostProjectionService service =
      new UsageCostProjectionService(modelRepository, configParser);

  /**
   * 测试意图：同一 Session 内模型切换时，每条 Assistant 结果都按自己所在 Turn 记录的型号与当前价格计价——证明解析来自祖先快照而不是
   * 「当前 branch settings」或最后一条消息。
   */
  @Test
  void projectsEachAssistantAgainstItsOwnTurnModelWithCurrentPrices() {
    stubModel(
        "provider-a",
        "model-a",
        pricing(new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("0.3")));
    stubModel(
        "provider-b",
        "model-b",
        pricing(new BigDecimal("1"), new BigDecimal("2"), BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1_000_000, 1_000_000, 1_000_000, 0, 0, 0, 3_000_000)),
                turnStart(TURN_B_ID, ASSISTANT_A_ID, settings("provider-b", "model-b")),
                user(USER_B_ID, TURN_B_ID),
                assistant(ASSISTANT_B_ID, USER_B_ID, new ModelUsage(2_000_000, 0, 0, 0, 0, 0, 2_000_000))));

    HarnessUsageCostDTO costA = costs.get(ASSISTANT_A_ID);
    assertEquals("USD", costA.getCurrency());
    assertEquals("18.300000000000", costA.getAmount());
    HarnessUsageCostDTO costB = costs.get(ASSISTANT_B_ID);
    assertEquals("USD", costB.getCurrency());
    assertEquals("2.000000000000", costB.getAmount());

    // 只有记录了模型用量的 ASSISTANT 结果才有投影；控制 Entry 与用户消息一律缺席。
    assertEquals(2, costs.size());
    assertFalse(costs.containsKey(USER_A_ID));
    assertFalse(costs.containsKey(TURN_A_ID));
    assertFalse(costs.containsKey(ROOT_ID));
    assertThrows(UnsupportedOperationException.class, () -> costs.put(USER_A_ID, costA));

    // 每个出现过的模型只查一次 catalog，且绝不写入。
    verify(modelRepository).getByProviderNameAndName("provider-a", "model-a");
    verify(modelRepository).getByProviderNameAndName("provider-b", "model-b");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 测试意图：模型已从 catalog 删除时该结果如实缺席（未计价），绝不退化成 0 费用或整次投影失败。 */
  @Test
  void keepsUnpricedEntriesAbsentInsteadOfFakingZero() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
    when(modelRepository.getByProviderNameAndName("provider-b", "model-b")).thenReturn(null);

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000)),
                turnStart(TURN_B_ID, ASSISTANT_A_ID, settings("provider-b", "model-b")),
                user(USER_B_ID, TURN_B_ID),
                assistant(ASSISTANT_B_ID, USER_B_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000))));

    assertEquals("1.000000000000", costs.get(ASSISTANT_A_ID).getAmount());
    assertNull(costs.get(ASSISTANT_B_ID));
    assertEquals(1, costs.size());
  }

  /**
   * 测试意图：压缩 Turn 实际调用的是 {@code compaction.executionModel}（fallback 时与用户所选 model 不同），因此必须以真实调用型号的
   * 价格计价，绝不用 settings.model 冒充。
   */
  @Test
  void pricesCompactionCallsWithTheRecordedExecutionModel() {
    // 用户所选 model-a 与压缩实际所用的 model-b 价格明显不同，任何误用 settings.model 都会立刻改变金额。
    stubModel("provider-a", "model-a", pricing(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO));
    stubModel("provider-b", "model-b", pricing(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                compactionTurnStart(TURN_A_ID, ROOT_ID),
                assistant(ASSISTANT_A_ID, TURN_A_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000))));

    assertEquals("1.000000000000", costs.get(ASSISTANT_A_ID).getAmount());
    verify(modelRepository).getByProviderNameAndName("provider-b", "model-b");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 测试意图：Assistant 之前没有任何 TURN_START 时回落到 ROOT 的 branch settings 快照，而不是放弃计价。 */
  @Test
  void resolvesRootSettingsWhenNoTurnPrecedesTheAssistant() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

    // 真实树上第一个 Turn 一定由 TURN_START 开启；这里显式固定 ROOT 回落分支这一独立事实。
    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                user(USER_A_ID, ROOT_ID),
                assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000))));

    assertEquals("1.000000000000", costs.get(ASSISTANT_A_ID).getAmount());
  }

  /**
   * 测试意图：同一模型的多条结果共用一次 catalog 读取——整棵 Entry tree 的投影成本与消息数无关，绝不能逐消息请求。
   */
  @Test
  void readsEachModelAtMostOncePerProjection() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000)),
                user(USER_B_ID, ASSISTANT_A_ID),
                assistant(ASSISTANT_B_ID, USER_B_ID, new ModelUsage(2_000_000, 0, 0, 0, 0, 0, 2_000_000))));

    assertEquals("1.000000000000", costs.get(ASSISTANT_A_ID).getAmount());
    assertEquals("2.000000000000", costs.get(ASSISTANT_B_ID).getAmount());
    verify(modelRepository, times(1)).getByProviderNameAndName("provider-a", "model-a");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 测试意图：祖先链不在入参里说明 durable 树已损坏，必须失败而不是拿错误的型号计价。 */
  @Test
  void failsClosedWhenAncestryIsMissingFromTheProjectedEntries() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

    List<Entry> orphaned =
        List.of(user(USER_A_ID, ROOT_ID), assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1, 0, 0, 0, 0, 0, 1)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.project(orphaned));
    assertTrue(error.getMessage().contains(ROOT_ID.toString()), error.getMessage());
  }

  /** 测试意图：catalog 定义本身非法属于数据损坏，照常抛出而不是静默把该 Entry 降级为「未计价」。 */
  @Test
  void propagatesMalformedCatalogConfigInsteadOfUnpricingTheEntry() {
    AgentModel broken = new AgentModel();
    broken.setProviderName("provider-a");
    broken.setName("model-a");
    broken.setConfigJson("{}");
    when(modelRepository.getByProviderNameAndName("provider-a", "model-a")).thenReturn(broken);

    List<Entry> entries =
        List.of(
            root(settings("provider-a", "model-a")),
            turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
            user(USER_A_ID, TURN_A_ID),
            assistant(ASSISTANT_A_ID, USER_A_ID, new ModelUsage(1_000_000, 0, 0, 0, 0, 0, 1_000_000)));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.project(entries));
    assertTrue(error.getMessage().startsWith("invalid agent model"), error.getMessage());
  }

  private void stubModel(String providerName, String modelName, AgentModelPricingDTO pricing) {
    AgentModel model = new AgentModel();
    model.setProviderName(providerName);
    model.setName(modelName);
    model.setConfigJson(configParser.encode(config(pricing)));
    when(modelRepository.getByProviderNameAndName(providerName, modelName)).thenReturn(model);
  }

  private static AgentModelPricingDTO pricing(
      BigDecimal inputPerMillionTokens,
      BigDecimal outputPerMillionTokens,
      BigDecimal cacheReadPerMillionTokens) {
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency("USD");
    pricing.setPricingTier("standard");
    pricing.setServiceTier("default");
    pricing.setServiceTierMultiplier(BigDecimal.ONE);
    pricing.setVersion("v1");
    pricing.setInputPerMillionTokens(inputPerMillionTokens);
    pricing.setOutputPerMillionTokens(outputPerMillionTokens);
    pricing.setCacheReadPerMillionTokens(cacheReadPerMillionTokens);
    pricing.setCacheWritePerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWriteLongPerMillionTokens(BigDecimal.ZERO);
    pricing.setReasoningPerMillionTokens(BigDecimal.ZERO);
    return pricing;
  }

  private static AgentModelConfigDTO config(AgentModelPricingDTO pricing) {
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(32768);
    limit.setOutput(4096);
    config.setLimit(limit);

    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(AgentModelInputModality.TEXT));
    config.setAbilities(abilities);

    AgentModelVariantDTO variant = new AgentModelVariantDTO();
    variant.setId("default");
    config.setVariants(List.of(variant));
    config.setDefaultVariant("default");
    config.setPricing(pricing);
    return config;
  }

  private static BranchSettings settings(String providerName, String modelName) {
    return new BranchSettings(
        "assistant", new ModelSelection(providerName, modelName, "default"), null);
  }

  private static Entry root(BranchSettings settings) {
    return new Entry(SESSION_ID, ROOT_ID, null, new RootPayload(settings), NOW);
  }

  /** COMPACTION Turn：settings 仍是用户所选 model，实际调用型号冻结在 compaction.executionModel。 */
  private static Entry compactionTurnStart(UUID id, UUID parentEntryId) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            settings("provider-a", "model-a"),
            THREAD_ID,
            null,
            null,
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.THRESHOLD,
                new ModelSelection("provider-b", "model-b", "default"),
                ROOT_ID,
                null,
                null)),
        NOW);
  }

  private static Entry turnStart(UUID id, UUID parentEntryId, BranchSettings settings) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID),
        NOW);
  }

  private static Entry user(UUID id, UUID parentEntryId) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        NOW);
  }

  private static Entry assistant(UUID id, UUID parentEntryId, ModelUsage usage) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi"))),
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, null),
            null),
        NOW);
  }
}
