package fun.fengwk.kkstudio.core.agent.runtime.provider;

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

    private final String modelName;
    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Integer maxOutputTokens;
    private final List<String> stopSequences;
    private final List<ToolSpecification> toolSpecifications;
    private final ToolChoice toolChoice;
    private final ResponseFormat responseFormat;

}
