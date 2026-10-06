package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * 读取时按「当前目录价」现算的费用投影。
 *
 * <p>它只是查询响应的一部分，绝不进入 durable 历史：Session Entry 的 payloadJson 与 AssistantMessageMetadata 都不保存费用，
 * 费用永远由 {@code ModelCost} 用记录的真实用量与当前 catalog 价格重算，因此同一个 Entry 在不同时间的投影可以随价格调整而变化。
 */
@Data
public class HarnessUsageCostDTO {

  /** 计价货币，来自命中价格的 model catalog 定义。 */
  private String currency;

  /** 精确十进制文本金额（不丢精度、不做展示舍入，展示格式由调用方决定）；求和绝不回落到浮点。 */
  private String amount;
}
