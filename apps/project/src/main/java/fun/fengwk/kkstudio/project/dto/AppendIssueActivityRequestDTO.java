package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 追加 Issue Activity 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendIssueActivityRequestDTO {

  private String expectedVersion;
  private String requestKey;

  /** 人工活动类型，只接受 COMMENT 与 INSTRUCTION；省略等同 COMMENT。 */
  private @Nullable IssueActivityRequestKind kind;

  private String body;
}
