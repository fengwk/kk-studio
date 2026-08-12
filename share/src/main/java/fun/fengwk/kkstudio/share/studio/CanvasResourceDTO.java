package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Canvas 资源的持久投影。immutable 资源行包含 ownerNodeId/resourceIndex， 与所属节点一起 upsert；kind 由 API 按
 * mediaType/textContent 派生，宽高/时长来自服务端媒体校验（可为 null，TEXT 资源没有 blob）。 契约绝不暴露 bucket/key/URI。
 */
@Data
public class CanvasResourceDTO {

  private String id;

  private String canvasId;

  private String ownerNodeId;

  private int resourceIndex;

  /** TEXT 资源内容在 textContent 中，无对象存储 blob；其余资源引用共享存储的持久 blob。 */
  private String blobId;

  private String name;

  private String textContent;

  private String kind;

  private String mediaType;

  private Long sizeBytes;

  private Integer width;

  private Integer height;

  private Long durationMs;

  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
