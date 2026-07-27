package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Session Entry 查询投影。 */
@Data
public class HarnessSessionEntryDTO {
  private String entryId;
  private String parentEntryId;
  private String entryType;
  private String payloadJson;
  private LocalDateTime createTime;
}
