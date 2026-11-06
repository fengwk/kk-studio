package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 只携带乐观锁版本的 Project 动作请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectVersionRequestDTO {

  private String expectedVersion;
}
