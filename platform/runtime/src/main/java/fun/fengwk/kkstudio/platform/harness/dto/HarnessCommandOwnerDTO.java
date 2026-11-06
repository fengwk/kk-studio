package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/** 不透明的产品归属身份；只用于授权，不承载展示字段。 */
@Data
public class HarnessCommandOwnerDTO {

  private String kind;
  private String id;
}
