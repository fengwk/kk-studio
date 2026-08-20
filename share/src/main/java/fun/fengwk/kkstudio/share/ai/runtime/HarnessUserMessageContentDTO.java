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
 * <p>{@code type} 仅允许 TEXT / ATTACHMENT / RESOURCE；mapper 按类型严格校验其余字段。ATTACHMENT 携带瞬时 {@code
 * uploadId}（READY 上传的 canonical UUID），由应用 use-case 在入队事务内物化为 durable RESOURCE。RESOURCE 只能复用目标
 * Session 已拥有的 blob ref。
 */
@Data
public class HarnessUserMessageContentDTO {

  /** 内容类型 discriminator：TEXT / ATTACHMENT / RESOURCE。 */
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

  /** 仅 RESOURCE 使用：目标 Session 已拥有的 blob id。 */
  private String blobId;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean blobIdFieldPresent;

  /** 仅 RESOURCE 使用：durable 展示名称。 */
  private String name;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean nameFieldPresent;

  /** 仅 RESOURCE 使用：可空的小型文本预览。 */
  private String preview;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean previewFieldPresent;

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

  @JsonSetter("blobId")
  public void setBlobId(Object blobId) {
    this.blobId = HarnessRuntimeDtoSupport.requireJsonString(blobId, "content.blobId");
    this.blobIdFieldPresent = true;
  }

  @JsonSetter("name")
  public void setName(Object name) {
    this.name = HarnessRuntimeDtoSupport.requireJsonString(name, "content.name");
    this.nameFieldPresent = true;
  }

  @JsonSetter("preview")
  public void setPreview(Object preview) {
    this.preview = HarnessRuntimeDtoSupport.requireJsonString(preview, "content.preview");
    this.previewFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasTextField() {
    return textFieldPresent;
  }

  @JsonIgnore
  public boolean hasUploadIdField() {
    return uploadIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasBlobIdField() {
    return blobIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasNameField() {
    return nameFieldPresent;
  }

  @JsonIgnore
  public boolean hasPreviewField() {
    return previewFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown USER_MESSAGE content field: " + name);
  }
}
