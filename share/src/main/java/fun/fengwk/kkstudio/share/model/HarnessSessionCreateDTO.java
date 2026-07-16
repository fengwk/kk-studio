package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Create root harness session request; agentDefinitionId is the AgentDefinition primary key. */
@Data
public class HarnessSessionCreateDTO {

    private String agentDefinitionId;
    private String title;
}
