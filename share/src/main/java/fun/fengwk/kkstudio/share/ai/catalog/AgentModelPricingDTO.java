package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/**
 * 完整模型价格定义：基础费率、按顺序应用的覆盖规则和按顺序应用的倍率规则。
 *
 * <p>所有 decimal 在 wire 上都是十进制字符串；未提供普通价格按 0、未提供倍率按 1、reasoning 缺省继承输出。
 */
@Data
public class AgentModelPricingDTO {

  private AgentModelRateSetDTO base;

  private List<AgentModelPricingOverrideDTO> overrides;

  private List<AgentModelPricingMultiplierDTO> multipliers;
}
