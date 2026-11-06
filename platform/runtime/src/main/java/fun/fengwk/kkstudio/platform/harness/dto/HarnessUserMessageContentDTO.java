package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.jspecify.annotations.Nullable;

/** 用户消息内容 union：文本、上传附件或既有 blob 资源。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HarnessUserMessageContentDTO.TextDTO.class, name = "TEXT"),
  @JsonSubTypes.Type(value = HarnessUserMessageContentDTO.AttachmentDTO.class, name = "ATTACHMENT"),
  @JsonSubTypes.Type(value = HarnessUserMessageContentDTO.ResourceDTO.class, name = "RESOURCE")
})
public sealed interface HarnessUserMessageContentDTO {

  /** 纯文本片段。 */
  @JsonTypeName("TEXT")
  record TextDTO(String text) implements HarnessUserMessageContentDTO {}

  /** 已上传附件；{@code imageTier} 只对图片有意义，缺省按平台默认档位处理。 */
  @JsonTypeName("ATTACHMENT")
  record AttachmentDTO(String uploadId, @Nullable HarnessImageInputTier imageTier)
      implements HarnessUserMessageContentDTO {}

  /** 既有 blob 资源；{@code preview} 为前端展示用的可空预览文本，{@code imageTier} 只对图片有意义。 */
  @JsonTypeName("RESOURCE")
  record ResourceDTO(
      String blobId,
      String name,
      @Nullable String preview,
      @Nullable HarnessImageInputTier imageTier)
      implements HarnessUserMessageContentDTO {}
}
