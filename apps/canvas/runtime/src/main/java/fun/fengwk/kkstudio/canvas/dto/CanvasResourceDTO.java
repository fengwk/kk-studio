package fun.fengwk.kkstudio.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;

import java.time.Instant;

/**
 * Canvas 资源的持久投影，按内容形态分为内联文本与 blob 两种。
 *
 * <p>immutable 资源行包含 ownerNodeId/resourceIndex，与所属节点一起 upsert；{@code kind} 由 API 按
 * mediaType/textContent 派生，宽高/时长来自服务端媒体校验。契约绝不暴露 bucket/key/URI。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasResourceDTO.Text.class, name = "TEXT"),
  @JsonSubTypes.Type(value = CanvasResourceDTO.Blob.class, name = "BLOB")
})
public sealed interface CanvasResourceDTO permits CanvasResourceDTO.Text, CanvasResourceDTO.Blob {

  String id();

  String canvasId();

  String ownerNodeId();

  int resourceIndex();

  String name();

  CanvasResourceKind kind();

  Instant createdAt();

  /** 内联文本资源：内容直接保存在资源行，没有对象存储 blob。 */
  record Text(
      String id,
      String canvasId,
      String ownerNodeId,
      int resourceIndex,
      String name,
      String textContent,
      CanvasResourceKind kind,
      Instant createdAt)
      implements CanvasResourceDTO {}

  /** blob 资源：内容引用共享存储的持久 blob，媒体事实来自 blob 行。 */
  record Blob(
      String id,
      String canvasId,
      String ownerNodeId,
      int resourceIndex,
      String blobId,
      String name,
      CanvasResourceKind kind,
      String mediaType,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Long sizeBytes,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Integer width,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Integer height,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Long durationMs,
      Instant createdAt)
      implements CanvasResourceDTO {}
}
