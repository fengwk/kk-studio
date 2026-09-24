package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Issue 人工评审请求 DTO。
 *
 * <p>人工审查与 Agent 审查执行同一业务动作：明确选择 {@code APPROVE} 或 {@code REQUEST_CHANGES} 并给出理由；被审查的 提交由服务端按当前
 * {@code IN_REVIEW} 的合格提交确定。{@code idempotencyKey} 用于重放同一审查动作：相同键的重复请求幂等， 与已落库决定矛盾的请求被拒绝。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewIssueRequestDTO {

  private String decision;
  private String reason;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String idempotencyKey;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
