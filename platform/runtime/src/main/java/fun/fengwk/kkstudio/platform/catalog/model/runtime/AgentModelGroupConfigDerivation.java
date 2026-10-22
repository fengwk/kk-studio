package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从成员单 Model 配置派生模型组的只读能力摘要。
 *
 * <p>上下文与输出取所有成员的最小值（输出再受最小上下文约束），能力取工具/推理的与和输入模态的交集。组没有自身价格或
 * Variant：派生结果不携带 pricing、defaultVariant 或 variants。
 */
public final class AgentModelGroupConfigDerivation {

  private AgentModelGroupConfigDerivation() {}

  public static AgentModelConfigDTO derive(List<AgentModelConfigDTO> members) {
    if (members == null || members.isEmpty()) {
      throw new IllegalArgumentException("group.members must not be empty");
    }
    int context = Integer.MAX_VALUE;
    int output = Integer.MAX_VALUE;
    boolean tools = true;
    boolean reasoning = true;
    Set<AgentModelInputModality> modalities = null;
    for (AgentModelConfigDTO member : members) {
      AgentModelLimitDTO limit = member.getLimit();
      context = Math.min(context, limit.getContext());
      output = Math.min(output, limit.getOutput());
      tools &= Boolean.TRUE.equals(member.getAbilities().getTools());
      reasoning &= Boolean.TRUE.equals(member.getAbilities().getReasoning());
      if (modalities == null) {
        modalities = new LinkedHashSet<>(member.getAbilities().getInputModalities());
      } else {
        modalities.retainAll(member.getAbilities().getInputModalities());
      }
    }
    if (modalities.isEmpty()) {
      throw new IllegalArgumentException("group.members have no common input modality");
    }

    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(context);
    limit.setOutput(Math.min(output, context));
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(tools);
    abilities.setReasoning(reasoning);
    abilities.setInputModalities(new ArrayList<>(modalities));

    AgentModelConfigDTO derived = new AgentModelConfigDTO();
    derived.setLimit(limit);
    derived.setAbilities(abilities);
    derived.setPricing(null);
    derived.setDefaultVariant(null);
    derived.setVariants(List.of());
    return derived;
  }
}
