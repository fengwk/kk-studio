package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Session Entry 查询投影；id 均为 strict positive decimal string。 */
@Data
public class HarnessSessionEntryDTO {
  private String entryId;
  private String sessionId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String parentEntryId;

  private String entryType;
  private String payloadJson;
  private Instant createTime;
}
