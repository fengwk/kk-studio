package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 不透明的产品归属身份；title 与 link 仅供展示，不用于授权。 */
@Data
public class HarnessCommandOwnerDTO {

  private String kind;
  private String id;
  private @Nullable String title;
  private @Nullable String link;
}
