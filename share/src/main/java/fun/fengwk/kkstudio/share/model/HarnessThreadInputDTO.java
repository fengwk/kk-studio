package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** ThreadInput 查询投影。 */
@Data
public class HarnessThreadInputDTO {
  private String inputId;
  private String threadId;
  private Long sequence;
  private String inputType;
  private String payloadJson;
  private String clientMessageId;
  private String status;
  private String appliedEntryId;
  private LocalDateTime resolvedAt;
  private String cancelledByStopId;
  private LocalDateTime createTime;
}
