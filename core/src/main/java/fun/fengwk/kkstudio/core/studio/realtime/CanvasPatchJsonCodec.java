package fun.fengwk.kkstudio.core.studio.realtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import fun.fengwk.kkstudio.studio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;

import java.util.Objects;

/**
 * Redis Canvas Patch JSON codec。
 *
 * <p>Redis 是可丢弃的实时投影，不复用 HTTP DTO；mixin 只补齐领域 sealed patch 的 {@code op} 判别字段并排除派生 getter，保持领域
 * record 的原生 round-trip。
 */
public final class CanvasPatchJsonCodec {

  private final ObjectMapper objectMapper;

  public CanvasPatchJsonCodec(ObjectMapper objectMapper) {
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addMixIn(CanvasGroupPatch.class, GroupPatchMixin.class)
            .addMixIn(CanvasNodePatch.class, NodePatchMixin.class)
            .addMixIn(CanvasLinkPatch.class, LinkPatchMixin.class)
            .addMixIn(CanvasPatch.class, PatchMixin.class)
            .addMixIn(CanvasResource.class, ResourceMixin.class);
  }

  public String encode(CanvasPatch patch) {
    Objects.requireNonNull(patch, "patch");
    try {
      return objectMapper.writeValueAsString(patch);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize canvas patch", error);
    }
  }

  public CanvasPatch decode(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("canvas patch JSON must not be blank");
    }
    try {
      return objectMapper.readValue(json, CanvasPatch.class);
    } catch (JsonProcessingException | RuntimeException error) {
      throw new IllegalArgumentException(
          "canvas changes stream patch is not canonical JSON", error);
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CanvasGroupPatch.Upsert.class, name = "UPSERT"),
    @JsonSubTypes.Type(value = CanvasGroupPatch.Remove.class, name = "REMOVE")
  })
  private interface GroupPatchMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CanvasNodePatch.Upsert.class, name = "UPSERT"),
    @JsonSubTypes.Type(value = CanvasNodePatch.Remove.class, name = "REMOVE")
  })
  private interface NodePatchMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CanvasLinkPatch.Upsert.class, name = "UPSERT"),
    @JsonSubTypes.Type(value = CanvasLinkPatch.Remove.class, name = "REMOVE")
  })
  private interface LinkPatchMixin {}

  private abstract static class PatchMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private abstract static class ResourceMixin {

    @JsonIgnore
    abstract boolean isBlob();

    @JsonIgnore
    abstract boolean isText();
  }
}
