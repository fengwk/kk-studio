package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 恢复业务阻塞请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoverIssueRequestDTO {

  private String expectedVersion;
  private String requestKey;
}
