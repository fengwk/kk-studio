package fun.fengwk.kkstudio.canvas.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * typed command 中节点资源数组的一个槽位意图，对应领域模型 {@code CanvasResourceInput}。
 *
 * <p>KEEP 复用编辑起点列表中已有的 Resource，维持它在当前节点中的身份；TEXT 与 BLOB 创建新的不可变 Resource 行， 文字内联在命令里，媒体则消费发起用户的
 * READY upload。命令不携带存储 bucket、对象 key 或可直接引用的 Blob id。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Keep.class, name = "KEEP"),
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Text.class, name = "TEXT"),
  @JsonSubTypes.Type(value = CanvasResourceInputDTO.Blob.class, name = "BLOB")
})
public sealed interface CanvasResourceInputDTO
    permits CanvasResourceInputDTO.Keep, CanvasResourceInputDTO.Text, CanvasResourceInputDTO.Blob {

  /** 保留编辑起点中已有的 Resource 槽位。 */
  record Keep(UUID resourceId) implements CanvasResourceInputDTO {}

  /** 新内联文本内容。 */
  record Text(String name, String textContent) implements CanvasResourceInputDTO {}

  /** 新媒体资源通过当前用户的 READY 上传建立持有边。 */
  record Blob(String name, UUID uploadId) implements CanvasResourceInputDTO {}
}
