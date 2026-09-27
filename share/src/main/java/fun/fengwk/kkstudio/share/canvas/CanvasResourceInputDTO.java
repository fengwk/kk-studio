package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * typed command 中节点资源数组的一个槽位意图，对应 canvas-core 的 {@code CanvasResourceInput}。
 *
 * <p>KEEP 复用编辑起点列表中已有的 Resource，维持它在当前节点中的身份；TEXT 与 BLOB 创建新的不可变 Resource 行， 文字内联在命令里，媒体则引用既有
 * Storage blob。命令不携带存储 bucket 或对象 key。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Keep.class, name = "KEEP"),
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Text.class, name = "TEXT"),
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Blob.class, name = "BLOB")
})
public sealed interface CanvasResourceInputDTO
    permits CanvasResourceInputDTO.Keep, CanvasResourceInputDTO.Text, CanvasResourceInputDTO.Blob {

  @JsonAnySetter
  default void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }

  /** 保留编辑起点中已有的 Resource 槽位。 */
  record Keep(String resourceId) implements CanvasResourceInputDTO {}

  /** 新内联文本内容。 */
  record Text(String name, String textContent) implements CanvasResourceInputDTO {}

  /** 新 Storage blob 引用。 */
  record Blob(String name, String blobId) implements CanvasResourceInputDTO {}
}
