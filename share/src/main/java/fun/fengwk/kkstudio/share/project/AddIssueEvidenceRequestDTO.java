package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人工发布 Issue 证据请求 DTO。
 *
 * <p>{@code uploadId} 指向一个已 READY 的上传（浏览器先 reserve/PUT/complete）：服务端在单个事务内以 {@code lockReady ->
 * retain Issue 引用 -> delete upload} 原子转移引用，不复制字节，也不接受客户端声明的文件名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddIssueEvidenceRequestDTO {

  private String uploadId;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
