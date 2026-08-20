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
 * <p>{@code type} 仅允许 TEXT / ATTACHMENT；mapper 按类型严格校验其余字段。ATTACHMENT 携带瞬时 {@code uploadId}（READY
 * 上传的 canonical UUID），由应用 use-case 在入队事务内物化为 durable RESOURCE。
 */
@Data
public class HarnessUserMessageContentDTO {

  /** 内容类型 discriminator：TEXT / ATTACHMENT。 */
  private String type;

  /** 仅 TEXT 使用。 */
  private String text;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean textFieldPresent;

  /** 仅 ATTACHMENT 使用：READY 上传的 canonical UUID string。 */
  private String uploadId;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean uploadIdFieldPresent;

  @JsonSetter("text")
  public void setText(String text) {
    this.text = text;
    this.textFieldPresent = true;
  }

  @JsonSetter("uploadId")
  public void setUploadId(Object uploadId) {
    this.uploadId = HarnessRuntimeDtoSupport.requireJsonString(uploadId, "content.uploadId");
    this.uploadIdFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasTextField() {
    return textFieldPresent;
  }

  @JsonIgnore
  public boolean hasUploadIdField() {
    return uploadIdFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown USER_MESSAGE content field: " + name);
  }
}
