package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentProviderDTO {

    private Long id;
    private String name;
    private String description;
    private String providerType;
    private String baseUrl;
    private String apiKey;
    private Long timeoutMillis;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
