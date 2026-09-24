package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 显式阻塞 Issue 请求 DTO（人为业务障碍，非基础设施失败）。
 *
 * <p>理由必填：阻塞会先收尾活动 Run 并停止自动推进，Issue 进入 {@code BLOCKED}，之后只能由人恢复或取消。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockIssueRequestDTO {

  private String expectedVersion;
  private String reason;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
