package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** 创建 Environment 请求 DTO。 */
@Data
public class EnvironmentCreateDTO {

  /** 必填环境名称（<= 64 字符，无空白或斜杠）。 */
  private String name;
}
