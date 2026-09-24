package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 追加 Issue Activity 请求 DTO（人为有来源的评论/指示）。
 *
 * <p>可选 {@code targetRole} 表达对已有参与者的定向补充输入（受控 @Agent）：{@code EXECUTOR} 或 {@code REVIEWER}； 未设置
 * {@code kind} 时，定向输入记为 {@code INSTRUCTION}，无目标评论记为 {@code COMMENT}。持久值是 Agent 身份、目标职责与
 * Activity，不扫描正文里的 {@code @xxx} 授予权限或提前开 Run。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendIssueActivityRequestDTO {

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String kind;

  private String body;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String targetRole;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String idempotencyKey;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
