package fun.fengwk.kkstudio.platform.harness.thread.query;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Session Entry 的读取时费用投影：只用 durable 记录的真实用量与「当前」catalog 价格现算，绝不写回任何历史。
 *
 * <p>费用边界：Assistant entry 只保存用量，本服务沿 parent 链取最近一次调用的 {@link ModelSelection}（TURN_START 的 {@code
 * compaction.executionModel}、否则该 Turn 的 branch settings，未进入 Turn 时回落 ROOT）乘以当前价格。同一次投影内每个出现过的
 * 模型至多查一次 catalog；无法定价（模型已删除）或定义非法（数据损坏）分别缺席与抛出，绝不伪装成 0 费用。
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
      ModelUsage usage = recordedUsage(entry);
      if (usage == null) {
        continue;
      }
      ModelPricing pricing = pricing(selectionAt(entry, entriesById), pricingByModel);
      if (pricing == null) {
        continue;
      }
      costs.put(entry.id(), usageCost(ModelCost.calculate(pricing, usage)));
    }
    return Map.copyOf(costs);
  }

  /** 只有 ASSISTANT MESSAGE 携带 assistantMetadata；USER / TOOL / 控制 Entry 一律没有可计价的用量。 */
  private static ModelUsage recordedUsage(Entry entry) {
    if (entry.payload() instanceof MessagePayload message && message.assistantMetadata() != null) {
      return message.assistantMetadata().usage();
    }
    return null;
  }

  /** 沿 parent 链找最近一次 branch settings 快照：它记录了这次模型调用实际使用的型号引用。 */
  private static ModelSelection selectionAt(Entry entry, Map<UUID, Entry> entriesById) {
    UUID cursor = entry.parentEntryId();
    while (cursor != null) {
      Entry ancestor = entriesById.get(cursor);
      if (ancestor == null) {
        throw new IllegalStateException(
            "entry ancestry is not contained in the projected entries: " + cursor);
      }
      if (ancestor.payload() instanceof TurnStartPayload turnStart) {
        // 压缩 Turn 实际调用的是 executionModel（fallback 时不同于 branch settings），必须按真实调用型号计价。
        return turnStart.compaction() == null
            ? turnStart.settings().model()
            : turnStart.compaction().executionModel();
      }
      if (ancestor.payload() instanceof RootPayload root) {
        return root.settings().model();
      }
      cursor = ancestor.parentEntryId();
    }
    throw new IllegalStateException(
        "entry " + entry.id() + " does not reach a ROOT entry through its parent chain");
  }

  /** 当前价格：模型已从 catalog 移除时返回 null（未计价），同一次投影内每个模型只查一次库。 */
  private ModelPricing pricing(ModelSelection selection, Map<String, ModelPricing> pricingByModel) {
    String key = selection.providerName() + '\u0000' + selection.modelName();
    if (pricingByModel.containsKey(key)) {
      return pricingByModel.get(key);
    }
    AgentModel model =
        modelRepository.getByProviderNameAndName(selection.providerName(), selection.modelName());
    ModelPricing pricing =
        model == null ? null : modelConfigParser.parse(model.getConfigJson()).pricing();
    pricingByModel.put(key, pricing);
    return pricing;
  }

  private static HarnessUsageCostDTO usageCost(ModelCost cost) {
    HarnessUsageCostDTO dto = new HarnessUsageCostDTO();
    dto.setCurrency(cost.currency());
    // 精确十进制文本：直接取 BigDecimal 的规范表示，不在读取侧做任何展示舍入。
    dto.setAmount(cost.total().toPlainString());
    return dto;
  }
}
