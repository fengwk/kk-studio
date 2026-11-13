package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从成员单 Model 配置派生模型组的只读能力摘要。
 *
 * <p>上下文与输出取所有成员的最小值（输出再受最小上下文约束）；输入上限只取声明了 {@code limit.input}
 * 的成员的最小值，没有成员声明或最小值不小于派生上下文时保持缺省。工具、推理、中途 system 消息和消息级 effort 能力均取成员交集，输入模态也取交集。组没有自身价格或
 * Variant：派生结果不携带 pricing、defaultVariant 或 variants。
 */
public final class ModelGroupConfigDerivation {

  private ModelGroupConfigDerivation() {}

  public static ModelConfigDTO derive(List<ModelConfigDTO> members) {
    if (members == null || members.isEmpty()) {
      throw new IllegalArgumentException("group.members must not be empty");
    }
    int context = Integer.MAX_VALUE;
    int output = Integer.MAX_VALUE;
    int declaredInput = Integer.MAX_VALUE;
    boolean anyInputDeclared = false;
    boolean tools = true;
    boolean reasoning = true;
    boolean midConversationSystemMessages = true;
    boolean messageLevelEffort = true;
    Set<ModelInputModality> modalities = null;
    for (ModelConfigDTO member : members) {
      ModelLimitDTO limit = member.getLimit();
      context = Math.min(context, limit.getContext());
      output = Math.min(output, limit.getOutput());
      if (limit.getInput() != null) {
        anyInputDeclared = true;
        declaredInput = Math.min(declaredInput, limit.getInput());
      }
      tools &= Boolean.TRUE.equals(member.getAbilities().getTools());
      reasoning &= Boolean.TRUE.equals(member.getAbilities().getReasoning());
      midConversationSystemMessages &= member.getAbilities().isMidConversationSystemMessages();
      messageLevelEffort &= member.getAbilities().isMessageLevelEffort();
      if (modalities == null) {
        modalities = new LinkedHashSet<>(member.getAbilities().getInputModalities());
      } else {
        modalities.retainAll(member.getAbilities().getInputModalities());
      }
    }
    if (modalities.isEmpty()) {
      throw new IllegalArgumentException("group.members have no common input modality");
    }

    ModelLimitDTO limit = new ModelLimitDTO();
    limit.setContext(context);
    limit.setOutput(Math.min(output, context));
    if (anyInputDeclared && declaredInput < context) {
      limit.setInput(declaredInput);
    }
    ModelAbilitiesDTO abilities = new ModelAbilitiesDTO();
    abilities.setTools(tools);
    abilities.setReasoning(reasoning);
    abilities.setMidConversationSystemMessages(midConversationSystemMessages);
    abilities.setMessageLevelEffort(messageLevelEffort);
    abilities.setInputModalities(new ArrayList<>(modalities));

    ModelConfigDTO derived = new ModelConfigDTO();
    derived.setLimit(limit);
    derived.setAbilities(abilities);
    derived.setPricing(null);
    derived.setDefaultVariant(null);
    derived.setVariants(List.of());
    return derived;
  }
}
