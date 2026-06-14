package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author fengwk
 */
@Builder
@Data
public class ModelRequestConfig {

    // TODO 通过配置表来动态获取不同供应商参数表

    /**
     * 请求使用的模型名。
     */
    private final String modelName;

    /**
     * 采样温度。
     */
    private final Double temperature;

    /**
     * nucleus sampling 参数。
     */
    private final Double topP;

    /**
     * top-k 采样参数。
     */
    private final Integer topK;

    /**
     * 频率惩罚参数。
     */
    private final Double frequencyPenalty;

    /**
     * 存在惩罚参数。
     */
    private final Double presencePenalty;

    /**
     * 最大输出 token 数。
     */
    private final Integer maxOutputTokens;

    /**
     * 停止序列列表。
     */
    private final List<String> stopSequences;

    /**
     * 本次请求允许调用的工具规格列表。
     */
    private final List<ToolSpecification> toolSpecifications;

    /**
     * 工具选择策略。
     */
    private final ToolChoice toolChoice;

    /**
     * 输出格式约束。
     */
    private final ResponseFormat responseFormat;

}
