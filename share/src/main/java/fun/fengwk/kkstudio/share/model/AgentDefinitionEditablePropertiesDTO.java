package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentDefinitionEditablePropertiesDTO {

    private String name;
    private String description;
    private String systemPrompt;
    private String defaultProvider;
    private String defaultModel;
    private String defaultVariant;
    private String toolsJson;

}
