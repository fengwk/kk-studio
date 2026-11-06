package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 重开 DONE Issue 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReopenIssueRequestDTO {

  private Long expectedVersion;
  private String requestKey;
}
