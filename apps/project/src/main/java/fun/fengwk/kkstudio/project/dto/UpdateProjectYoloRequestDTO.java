package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 独立编辑项目 YOLO 策略请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectYoloRequestDTO {

  private String expectedVersion;
  private Boolean yoloEnabled;
}
