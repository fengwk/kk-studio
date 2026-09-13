package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建目录请求体（{@code POST /api/cloud/directories}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudDirectoryCreateRequestDTO {

  /** 目标目录的规范虚拟绝对路径。 */
  private String path;

  /** 是否递归创建缺失的父级目录；默认为 false。 */
  private Boolean recursive;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
