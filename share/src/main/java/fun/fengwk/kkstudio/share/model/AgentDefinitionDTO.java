package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentDefinitionDTO {

    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private Long defaultProviderId;
    private String defaultProviderName;
    private Long defaultModelId;
    private String defaultModelName;
    private String defaultVariant;
    private String toolsJson;
    private String subagentsJson;
    private String skillsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
