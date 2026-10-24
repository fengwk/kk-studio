package fun.fengwk.kkstudio.platform.harness.thread.query;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Session Entry 的读取时费用投影：只用 durable 记录的真实用量与「当前」catalog 价格现算，绝不写回任何历史。
 *
 * <p>费用边界：Assistant / COMPACTION 的 metadata 冻结真实执行 ModelSelection、请求时间与 Provider 报告的
 * serviceTier；本服务只按这些事实与当前 catalog 价格现算，绝不沿 branch settings 或组配置兜底。同一次投影内每个模型至多查一次
 * catalog；模型已删除时缺席，数据损坏时抛出。
 */
@Component
public class UsageCostProjectionService {

  private final AgentModelRepository modelRepository;
  private final AgentModelRuntimeConfigParser modelConfigParser;

  public UsageCostProjectionService(
      AgentModelRepository modelRepository, AgentModelRuntimeConfigParser modelConfigParser) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
  }

  /**
   * 返回 {@code entryId -> 费用投影} 的不可变映射；无法定价的 Entry 不出现在结果里。
   *
   * @param entries 同一 Session 的 Entry 集合，且包含每个待计价 Entry 的完整祖先链
   */
  public Map<UUID, HarnessUsageCostDTO> project(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    Map<UUID, Entry> entriesById = new HashMap<>(entries.size());
    for (Entry entry : entries) {
      entriesById.put(entry.id(), entry);
    }
    Map<String, ModelPricing> pricingByModel = new HashMap<>();
    Map<UUID, HarnessUsageCostDTO> costs = new HashMap<>();
    for (Entry entry : entries) {
      AssistantMessageMetadata metadata = recordedMetadata(entry);
      if (metadata == null) {
        continue;
      }
      if (metadata.model() == null) {
        throw new IllegalStateException(
            "assistant metadata is missing the actual model selection: entry=" + entry.id());
      }
      ModelPricing pricing = pricing(metadata.model(), pricingByModel);
      if (pricing == null) {
        continue;
      }
      ModelCost cost =
          ModelCost.calculate(
              pricing, metadata.usage(), metadata.requestedAt(), metadata.responseServiceTier());
      if (cost != null) {
        costs.put(entry.id(), usageCost(cost));
      }
    }
    return Map.copyOf(costs);
  }

  /**
   * ASSISTANT MESSAGE 与 COMPACTION 结果都可能携带 assistantMetadata；只有真正发生过的模型输出才有用量，USER / TOOL / 无
   * metadata 的控制 Entry 一律没有可计价的用量。
   */
  private static AssistantMessageMetadata recordedMetadata(Entry entry) {
    if (entry.payload() instanceof MessagePayload message && message.assistantMetadata() != null) {
      return message.assistantMetadata();
    }
    if (entry.payload() instanceof CompactionPayload compaction
        && compaction.assistantMetadata() != null) {
      return compaction.assistantMetadata();
    }
    return null;
  }

  /** 当前价格：模型已从 catalog 移除时返回 null（未计价），同一次投影内每个模型只查一次库。 */
  private ModelPricing pricing(ModelSelection selection, Map<String, ModelPricing> pricingByModel) {
    String key = selection.modelName();
    if (pricingByModel.containsKey(key)) {
      return pricingByModel.get(key);
    }
    AgentModel model = modelRepository.getByName(selection.modelName());
    if (model != null && model.kind() != AgentModelKind.MODEL) {
      throw new IllegalStateException(
          "assistant metadata must reference an executed single model, got group: "
              + selection.modelName());
    }
    ModelPricing pricing =
        model == null ? null : modelConfigParser.parse(model.configJson()).pricing();
    pricingByModel.put(key, pricing);
    return pricing;
  }

  private static HarnessUsageCostDTO usageCost(ModelCost cost) {
    HarnessUsageCostDTO dto = new HarnessUsageCostDTO();
    dto.setCurrency(cost.currency());
    // 精确十进制文本：直接取 BigDecimal 的规范表示，每 Entry 微成本精确保留，展示层的最终定标由调用方负责。
    dto.setAmount(cost.total().toPlainString());
    return dto;
  }
}
