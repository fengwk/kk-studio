package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 不透明的产品归属身份；title 与 link 仅供展示，不用于授权。 */
@Data
public class HarnessCommandOwnerDTO {

  private String kind;
  private String id;
  private @Nullable String title;
  private @Nullable String link;

  @JsonSetter("kind")
  public void setKind(Object value) {
    this.kind = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.kind");
  }

  @JsonSetter("id")
  public void setId(Object value) {
    this.id = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.id");
  }

  @JsonSetter("title")
  public void setTitle(Object value) {
    this.title = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.title");
  }

  @JsonSetter("link")
  public void setLink(Object value) {
    this.link = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.link");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command owner field: " + name);
  }
}
