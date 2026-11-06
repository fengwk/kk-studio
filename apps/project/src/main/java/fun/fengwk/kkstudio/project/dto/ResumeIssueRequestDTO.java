package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 显式解除控制暂停请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeIssueRequestDTO {

  private Long expectedVersion;
  private String requestKey;
}
