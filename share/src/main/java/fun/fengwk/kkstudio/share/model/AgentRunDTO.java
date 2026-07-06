package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentRunDTO {

    private String runId;
    private String sessionId;
    private String triggerEventId;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
