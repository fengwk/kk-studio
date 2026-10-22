package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 结构化 Agent 模型配置。这是 public AgentModel API 对外暴露并持久化到 {@code agent_model.config} JSONB 列的唯一事实源。
 *
 * <p>Schema 如下：
 *
 * <pre>
 * limit:        { context: positive int, output: positive int &lt;= context }
 * abilities:    { tools: boolean, reasoning: boolean, inputModalities: non-empty list }
 * pricing:      { base: { input, output, cacheRead, cacheWrite, cacheWriteByTtl, reasoning },
 *                overrides: [], multipliers: [] }，可缺省并归一为完整零定义
 * defaultVariant: existing variant id
 * variants:     non-empty list with at least {@code id} (optional reasoningEffort)
 * </pre>
 *
 * <p>{@code limit.output} 是模型级单次输出预算的事实源：每次请求的输出上限由它与当前剩余上下文计算，绝不放入 variant。
 */
@Data
public class AgentModelConfigDTO {

  /** 必填 Token 限制：context 与 output 均为正整数且 output ≤ context。 */
  private AgentModelLimitDTO limit;

  /** 必填能力声明：tools/reasoning 必填，inputModalities 非空且去重。 */
  private AgentModelAbilitiesDTO abilities;

  /** 定价定义；可缺省，单 Model parser 会归一为完整零定义（reasoning 缺省仍继承 output）；组的派生摘要为 null。 */
  private @Nullable AgentModelPricingDTO pricing;

  /** 单 Model 必填默认变体 id：非空白、无环绕空白，且必须匹配 {@code variants[].id}；组的派生摘要为 null。 */
  private @Nullable String defaultVariant;

  /** 必填非空变体列表；元素 id 必须非空白且唯一。 */
  private List<AgentModelVariantDTO> variants;
}
