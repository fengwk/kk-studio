package fun.fengwk.kkstudio.platform.storage.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * 上传公开表示：{@code id} 是持久的 upload 句柄，{@code expiresAt} 是响应时刻的上传清理截止快照。
 *
 * <p>多形态由 {@code type} 判别：{@code PENDING} 携带浏览器直传预签名 PUT，{@code READY} 携带已绑定的 blob id。响应刻意不暴露
 * bucket 与对象物理 key。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = StorageUploadPendingDTO.class, name = "PENDING"),
  @JsonSubTypes.Type(value = StorageUploadReadyDTO.class, name = "READY")
})
public sealed interface StorageUploadDTO permits StorageUploadPendingDTO, StorageUploadReadyDTO {

  /** 上传 id（canonical uuid）。 */
  String getId();

  /**
   * 上传清理截止时刻快照：按响应时刻的 upload TTL 与 {@code created_at} 换算的展示值。
   *
   * <p>它只是快照，不是过期权威事实；服务端始终以 {@code created_at + 当下 upload TTL} 判定过期，因此之后修改 TTL 会立即改变实际上传有效期， 但不会
   * 回改这里已经返回的值。
   */
  Instant getExpiresAt();

  String getFormat();

  Integer getOriginalWidth();

  Integer getOriginalHeight();

  Integer getStoredWidth();

  Integer getStoredHeight();
}
