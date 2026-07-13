package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentSessionDTO {

    private String sessionId;
    private Long agentId;
    private String agentName;
    private String title;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
