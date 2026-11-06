package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 业务阻塞 Issue 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockIssueRequestDTO {

  private Long expectedVersion;
  private String requestKey;
  private String reason;
}
