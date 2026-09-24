package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Issue 公开证据的公开传输对象。
 *
 * <p>一条记录是 Issue 持有的一个已发布 Blob 引用：{@code origin} 是 {@code EXECUTOR}（执行者最终答复明确引用）或 {@code
 * HUMAN}（人工上传）；{@code uri} 是规范资源 URI {@code kkstudio:/resources/<blobId>}，但它不是权限凭据——读取仍需当前 Session
 * 持有该引用（由平台按已发布证据幂等授予）。{@code name} 只在人工上传时非空（权威上传文件名）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueEvidenceDTO {

  private String issueId;
  private String blobId;
  private String uri;
  private String origin;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String name;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String runId;

  private String publishedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
