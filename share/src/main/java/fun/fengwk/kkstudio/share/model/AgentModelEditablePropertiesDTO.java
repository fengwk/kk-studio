package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentModelEditablePropertiesDTO {

    private String name;
    private String description;
    private String capabilitiesJson;
    private String limitJson;
    private String pricingJson;
    private String defaultVariant;
    private String variantsJson;

}
