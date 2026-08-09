package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * USER_MESSAGE 的结构化内容单元。
 *
 * <p>{@code type} 仅允许 TEXT / IMAGE / AUDIO / VIDEO；mapper 按类型严格校验其余字段。
 */
@Data
public class HarnessUserMessageContentDTO {

  /** 内容类型 discriminator：TEXT / IMAGE / AUDIO / VIDEO。 */
  private String type;

  /** 仅 TEXT 使用。 */
  private String text;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean textFieldPresent;

  /** 仅 IMAGE / AUDIO / VIDEO 使用。 */
  private String mediaType;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean mediaTypeFieldPresent;

  /** 仅 IMAGE / AUDIO / VIDEO 使用的 URI 或内联编码。 */
  private String source;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean sourceFieldPresent;

  @JsonSetter("text")
  public void setText(String text) {
    this.text = text;
    this.textFieldPresent = true;
  }

  @JsonSetter("mediaType")
  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
    this.mediaTypeFieldPresent = true;
  }

  @JsonSetter("source")
  public void setSource(String source) {
    this.source = source;
    this.sourceFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasTextField() {
    return textFieldPresent;
  }

  @JsonIgnore
  public boolean hasMediaTypeField() {
    return mediaTypeFieldPresent;
  }

  @JsonIgnore
  public boolean hasSourceField() {
    return sourceFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown USER_MESSAGE content field: " + name);
  }
}
