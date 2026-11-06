package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 人工终止 Issue 执行请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StopIssueRequestDTO {

  private Long expectedVersion;
  private String requestKey;

  private @Nullable String detail;
}
