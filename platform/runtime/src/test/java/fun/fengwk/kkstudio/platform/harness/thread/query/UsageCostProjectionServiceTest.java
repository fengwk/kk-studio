package fun.fengwk.kkstudio.platform.harness.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 读取时费用投影契约：按每个 ASSISTANT 结果 metadata 记录的真实型号取<b>当前</b> catalog 价格并现算，且整次投影只读。
 *
 * <p>测试固定三件容易被后续实现悄悄弄错的事实：模型中途切换时逐条按各自当时的型号计价；无法定价（模型已删除或定义损坏）绝不伪装成 0 费用；每个出现过的模型在一次投影里至多查一次
 * catalog。
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

  private static final ModelSelection MODEL_A = new ModelSelection("model-a", "default");
  private static final ModelSelection MODEL_B = new ModelSelection("model-b", "default");

  private final AgentModelRepository modelRepository = mock(AgentModelRepository.class);
  private final AgentModelRuntimeConfigParser configParser =
      new AgentModelRuntimeConfigParser(JsonMapper.builder().build());

  private final UsageCostProjectionService service =
      new UsageCostProjectionService(modelRepository, configParser);

  /**
   * 测试意图：同一 Session 内模型切换时，每条 Assistant 结果都按自己 metadata 记录的型号与当前价格计价，而不是「当前 branch
   * settings」或最后一条消息。
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
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1_000_000, 1_000_000, 1_000_000, 0, Map.of(), 0L, 3_000_000L)),
                turnStart(TURN_B_ID, ASSISTANT_A_ID, settings("provider-b", "model-b")),
                user(USER_B_ID, TURN_B_ID),
                assistant(
                    ASSISTANT_B_ID,
                    USER_B_ID,
                    MODEL_B,
                    new ModelUsage(2_000_000, 0, 0, 0, Map.of(), 0L, 2_000_000L))));

    HarnessUsageCostDTO costA = costs.get(ASSISTANT_A_ID);
    assertEquals("USD", costA.getCurrency());
    assertEquals("18.3", costA.getAmount());
    HarnessUsageCostDTO costB = costs.get(ASSISTANT_B_ID);
    assertEquals("USD", costB.getCurrency());
    assertEquals("2", costB.getAmount());

    // 只有记录了模型用量的 ASSISTANT 结果才有投影；控制 Entry 与用户消息一律缺席。
    assertEquals(2, costs.size());
    assertFalse(costs.containsKey(USER_A_ID));
    assertFalse(costs.containsKey(TURN_A_ID));
    assertFalse(costs.containsKey(ROOT_ID));
    assertThrows(UnsupportedOperationException.class, () -> costs.put(USER_A_ID, costA));

    // 每个出现过的模型只查一次 catalog，且绝不写入。
    verify(modelRepository).getByName("model-a");
    verify(modelRepository).getByName("model-b");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 组选择只负责路由；费用必须使用 metadata 记录的实际成员型号，而不是按所选组计价。 */
  @Test
  void pricesActualGroupMemberInsteadOfSelectedGroup() {
    stubModel(
        "group-provider",
        "model-group",
        pricing(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO));
    stubModel(
        "member-provider",
        "member-a",
        pricing(new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("group-provider", "model-group")),
                turnStart(TURN_A_ID, ROOT_ID, settings("group-provider", "model-group")),
                user(USER_A_ID, TURN_A_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    new ModelSelection("member-a", "default"),
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    verify(modelRepository).getByName("member-a");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 测试意图：模型已从 catalog 删除时该结果如实缺席（未计价），绝不退化成 0 费用或整次投影失败。 */
  @Test
  void keepsUnpricedEntriesAbsentInsteadOfFakingZero() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
    when(modelRepository.getByName("model-b")).thenReturn(null);

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L)),
                turnStart(TURN_B_ID, ASSISTANT_A_ID, settings("provider-b", "model-b")),
                user(USER_B_ID, TURN_B_ID),
                assistant(
                    ASSISTANT_B_ID,
                    USER_B_ID,
                    MODEL_B,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    assertNull(costs.get(ASSISTANT_B_ID));
    assertEquals(1, costs.size());
  }

  /** 测试意图：执行过的单模型改名或删除后同名被模型组复用时，组没有该模型的价格：该结果如实缺席，不让整段历史读取失败。 */
  @Test
  void keepsEntriesAbsentWhenTheExecutedModelNameNowBelongsToAGroup() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
    when(modelRepository.getByName("model-b"))
        .thenReturn(AgentModel.builder().kind(AgentModelKind.GROUP).name("model-b").build());

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L)),
                turnStart(TURN_B_ID, ASSISTANT_A_ID, settings("provider-b", "model-b")),
                user(USER_B_ID, TURN_B_ID),
                assistant(
                    ASSISTANT_B_ID,
                    USER_B_ID,
                    MODEL_B,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
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
    stubModel(
        "provider-a", "model-a", pricing(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO));
    stubModel("provider-b", "model-b", pricing(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                compactionTurnStart(TURN_A_ID, ROOT_ID),
                assistant(
                    ASSISTANT_A_ID,
                    TURN_A_ID,
                    MODEL_B,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    verify(modelRepository).getByName("model-b");
    verifyNoMoreInteractions(modelRepository);
  }

  /**
   * 测试意图：压缩结果本身是真实模型输出，其 canonical metadata 的用量同样按 executionModel 计价；纯摘要（无 metadata）绝不 伪装成 0
   * 用量而进入投影。
   */
  @Test
  void pricesCompactionResultMetadataWithTheRecordedExecutionModel() {
    stubModel(
        "provider-a", "model-a", pricing(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO));
    stubModel("provider-b", "model-b", pricing(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                compactionTurnStart(TURN_A_ID, ROOT_ID),
                compactionResult(
                    ASSISTANT_A_ID,
                    TURN_A_ID,
                    MODEL_B,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L)),
                compactionResult(ASSISTANT_B_ID, ASSISTANT_A_ID, MODEL_B, null)));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    assertNull(costs.get(ASSISTANT_B_ID));
    assertEquals(1, costs.size());
    verify(modelRepository).getByName("model-b");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 没有 TURN_START 时也必须按实际型号而非 ROOT 的选择计价。 */
  @Test
  void pricesRecordedModelWhenNoTurnPrecedesTheAssistant() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-b", "model-b")),
                user(USER_A_ID, ROOT_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    verify(modelRepository).getByName("model-a");
    verifyNoMoreInteractions(modelRepository);
  }

  /** 测试意图：同一模型的多条结果共用一次 catalog 读取——整棵 Entry tree 的投影成本与消息数无关，绝不能逐消息请求。 */
  @Test
  void readsEachModelAtMostOncePerProjection() {
    stubModel("provider-a", "model-a", pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L)),
                user(USER_B_ID, ASSISTANT_A_ID),
                assistant(
                    ASSISTANT_B_ID,
                    USER_B_ID,
                    MODEL_A,
                    new ModelUsage(2_000_000, 0, 0, 0, Map.of(), 0L, 2_000_000L))));

    assertEquals("1", costs.get(ASSISTANT_A_ID).getAmount());
    assertEquals("2", costs.get(ASSISTANT_B_ID).getAmount());
    verify(modelRepository, times(1)).getByName("model-a");
    verifyNoMoreInteractions(modelRepository);
  }

  /**
   * 测试意图：投影只做精确十进制求和，绝不在读取侧定标或舍入；远小于 {@code 1e-12} 的微成本必须原样保留，不能被截断成 0 或补零成固定 小数位。展示层的最终定标（如最多 6
   * 位小数）由调用方在累加后决定。
   */
  @Test
  void preservesSubPicocentAmountsWithoutReadTimeRounding() {
    stubModel(
        "provider-a",
        "model-a",
        pricing(new BigDecimal("0.0000001"), BigDecimal.ZERO, BigDecimal.ZERO));

    Map<UUID, HarnessUsageCostDTO> costs =
        service.project(
            List.of(
                root(settings("provider-a", "model-a")),
                turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
                user(USER_A_ID, TURN_A_ID),
                assistant(
                    ASSISTANT_A_ID,
                    USER_A_ID,
                    MODEL_A,
                    new ModelUsage(1, 0, 0, 0, Map.of(), 0L, 1L))));

    String amount = costs.get(ASSISTANT_A_ID).getAmount();
    // 单价 1e-7 × 1 token ÷ 1e6 = 1e-13，精确文本必须保留全部有效位。
    assertEquals("0.0000000000001", amount);
    assertTrue(new BigDecimal(amount).signum() > 0, amount);
  }

  /** 成功输出的 metadata 缺少实际执行型号属于数据损坏，必须失败而不是猜测或静默跳过。 */
  @Test
  void failsClosedWhenAssistantMetadataHasNoActualModel() {
    List<Entry> entries =
        List.of(user(USER_A_ID, ROOT_ID), assistantWithoutModel(ASSISTANT_A_ID, USER_A_ID));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.project(entries));
    assertEquals(
        "assistant metadata is missing the actual model selection: entry=" + ASSISTANT_A_ID,
        error.getMessage());
    verifyNoInteractions(modelRepository);
  }

  /** Compaction 的 metadata 同样必须记录实际型号，不能通过 Turn 设置猜测。 */
  @Test
  void rejectsCompactionMetadataWithoutActualModel() {
    List<Entry> entries =
        List.of(
            root(settings("provider-a", "model-a")),
            compactionTurnStart(TURN_A_ID, ROOT_ID),
            compactionResult(
                ASSISTANT_A_ID, TURN_A_ID, null, new ModelUsage(1, 0, 0, 0, Map.of(), 0L, 1L)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.project(entries));
    assertEquals(
        "assistant metadata is missing the actual model selection: entry=" + ASSISTANT_A_ID,
        error.getMessage());
    verifyNoInteractions(modelRepository);
  }

  /** 测试意图：catalog 定义本身非法属于数据损坏，照常抛出而不是静默把该 Entry 降级为「未计价」。 */
  @Test
  void propagatesMalformedCatalogConfigInsteadOfUnpricingTheEntry() {
    AgentModel broken =
        AgentModel.builder()
            .kind(AgentModelKind.MODEL)
            .providerName("provider-a")
            .name("model-a")
            .configJson("{}")
            .build();
    when(modelRepository.getByName("model-a")).thenReturn(broken);

    List<Entry> entries =
        List.of(
            root(settings("provider-a", "model-a")),
            turnStart(TURN_A_ID, ROOT_ID, settings("provider-a", "model-a")),
            user(USER_A_ID, TURN_A_ID),
            assistant(
                ASSISTANT_A_ID,
                USER_A_ID,
                MODEL_A,
                new ModelUsage(1_000_000, 0, 0, 0, Map.of(), 0L, 1_000_000L)));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.project(entries));
    assertTrue(error.getMessage().startsWith("invalid agent model"), error.getMessage());
  }

  private void stubModel(String providerName, String modelName, AgentModelPricingDTO pricing) {
    AgentModel model =
        AgentModel.builder()
            .kind(AgentModelKind.MODEL)
            .providerName(providerName)
            .name(modelName)
            .configJson(configParser.encode(config(pricing)))
            .build();
    when(modelRepository.getByName(modelName)).thenReturn(model);
  }

  private static AgentModelPricingDTO pricing(
      BigDecimal inputPerMillionTokens,
      BigDecimal outputPerMillionTokens,
      BigDecimal cacheReadPerMillionTokens) {
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    AgentModelRateSetDTO base = new AgentModelRateSetDTO();
    base.setInput(inputPerMillionTokens);
    base.setOutput(outputPerMillionTokens);
    base.setCacheRead(cacheReadPerMillionTokens);
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(null);
    pricing.setBase(base);
    pricing.setOverrides(List.of());
    pricing.setMultipliers(List.of());
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
    return new BranchSettings("assistant", new ModelSelection(modelName, "default"), null);
  }

  private static Entry root(BranchSettings settings) {
    return new Entry(ROOT_ID, SESSION_ID, null, new RootPayload(settings), NOW);
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
                new ModelSelection("model-b", "default"),
                1024L,
                ROOT_ID,
                null,
                null,
                new UUID(0L, 50L),
                new UUID(0L, 51L)),
            null),
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

  private static Entry assistant(
      UUID id, UUID parentEntryId, ModelSelection model, ModelUsage usage) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, usage, null, model, NOW, null),
            null),
        NOW);
  }

  private static Entry assistantWithoutModel(UUID id, UUID parentEntryId) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, new ModelUsage(1, 0, 0, 0, Map.of(), 0L, 1L), null),
            null),
        NOW);
  }

  /** COMPACTION 结果 Entry：真实模型输出携带 canonical metadata；纯摘要 usage 为 null。 */
  private static Entry compactionResult(
      UUID id, UUID parentEntryId, ModelSelection model, ModelUsage usage) {
    return new Entry(
        id,
        SESSION_ID,
        parentEntryId,
        new CompactionPayload(
            "compacted summary",
            usage == null
                ? null
                : new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE, usage, null, model, NOW, null)),
        NOW);
  }
}
