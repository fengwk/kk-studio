package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
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
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String blobId;

  private String name;

  /** required-nullable：媒体资源无文本内容，必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String textContent;

  private String kind;

  /** required-nullable：TEXT 资源无媒体事实，必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String mediaType;

  /** required-nullable：媒体校验缺失时显式输出 null；非 null 时 wire 为规范非负十进制字符串。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Long sizeBytes;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer width;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer height;

  /** required-nullable：媒体校验缺失时显式输出 null；非 null 时 wire 为规范非负十进制字符串。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Long durationMs;

  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
