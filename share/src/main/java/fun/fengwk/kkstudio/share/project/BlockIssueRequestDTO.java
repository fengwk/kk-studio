package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 阻塞 Issue 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockIssueRequestDTO {

  private String expectedVersion;
  private String requestKey;
  private String reason;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
