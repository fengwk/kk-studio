package fun.fengwk.kkstudio.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ModelInfo 表示一个模型的基础信息与可用 variants。
 *
 * 语义说明：
 * - ModelInfo 记录模型的稳定信息，不直接承载运行时请求参数。
 * - 运行时请求参数全部由 variant 提供。
 *
 * @author fengwk
 */
@Builder
@Data
public class ModelInfo {

    /**
     * provider 名称。
     */
    private final String provider;

    /**
     * 模型名称。
     */
    private final String name;

    /**
     * 模型展示名。
     */
    private final String displayName;

    /**
     * 模型能力描述。
     */
    private final ModelCapabilities capabilities;

    /**
     * 模型限制信息。
     */
    private final ModelLimit limit;

    /**
     * 模型价格信息。
     */
    private final ModelPricing pricing;

    /**
     * 默认 variant 名称。
     */
    private final String defaultVariant;

    /**
     * 模型支持的变体列表。
     */
    private final List<Variant> variants;

}
