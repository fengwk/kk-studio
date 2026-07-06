package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentSessionEventDTO {

    private String eventId;
    private String sessionId;
    private String parentEventId;
    private String runId;
    private String eventType;
    private String payloadType;
    private String payloadJson;
    private LocalDateTime createTime;

}
