package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人工恢复 {@code BLOCKED} Issue 请求 DTO。
 *
 * <p>要求不变时恢复为 {@code TODO}（{@code toBacklog=false}），需先改要求时恢复为 {@code BACKLOG}（{@code
 * toBacklog=true}）再编辑。恢复记 Activity 并开启新的打回计数区间，未处理的旧提交不重新获得审查资格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoverIssueRequestDTO {

  private String expectedVersion;
  private Boolean toBacklog;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String comment;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
