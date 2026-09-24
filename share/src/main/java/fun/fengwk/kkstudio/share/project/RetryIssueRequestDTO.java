package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 重试 Issue 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryIssueRequestDTO {

  private String idempotencyKey;

  /**
   * 人工核对说明：最新 Run 为 UNKNOWN 时必填，用于记录已核查的残留模型/工具调用与外部副作用，并写入 RETRY Activity 正文；最新 Run 为 FAILED 时可省略。
   */
  private String verification;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
