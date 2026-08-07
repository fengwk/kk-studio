package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/**
 * Agent 模型的功能能力声明。
 *
 * <p>{@code inputModalities} 是 {@link AgentModelInputModality} 的非空子集；{@code tools} 与 {@code
 * reasoning} 是直接驱动运行时行为的一等 boolean。
 */
@Data
public class AgentModelAbilitiesDTO {

  /** 必填 boolean：模型是否支持工具调用（第一类能力，直接驱动运行时 tool binding 行为）。 */
  private Boolean tools;

  /** 必填 boolean：模型是否支持 reasoning（直接驱动运行时 reasoning effort 下发行为）。 */
  private Boolean reasoning;

  /** 必填非空输入模态列表；元素为 {@link AgentModelInputModality} 枚举（TEXT/IMAGE/AUDIO/VIDEO/DOCUMENT），不可重复。 */
  private List<AgentModelInputModality> inputModalities;
}
