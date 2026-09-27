package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 项目版本 CAS 请求 DTO，用于归档、解归档、删除等操作。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectVersionRequestDTO {

  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}
