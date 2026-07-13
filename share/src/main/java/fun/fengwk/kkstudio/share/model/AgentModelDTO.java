package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentModelDTO {

    private Long id;
    private Long providerId;
    private String providerName;
    private String name;
    private String description;
    private String defaultVariant;
    private String variantsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
