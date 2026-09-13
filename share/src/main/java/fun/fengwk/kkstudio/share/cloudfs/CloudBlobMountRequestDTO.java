package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 挂载/关联 Blob 上传请求体（{@code POST /api/cloud/blobs}）。
 *
 * <p>原子消费 READY 状态的 upload 并创建/保留对应的 BLOB 节点。{@code expectedAbsent} 必须为 true。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudBlobMountRequestDTO {

  /** 目标 BLOB 节点的规范虚拟绝对路径。 */
  private String path;

  /** 待消费的 READY 状态 upload UUID。 */
  private String uploadId;

  /** 显式断言目标节点当前不存在（必须为 true）。 */
  private Boolean expectedAbsent;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
