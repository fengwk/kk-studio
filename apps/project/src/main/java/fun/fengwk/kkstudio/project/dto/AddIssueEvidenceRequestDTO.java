package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** 人工发布 Issue 证据请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddIssueEvidenceRequestDTO {

  /** 待发布上传的 id。 */
  private UUID uploadId;
}
