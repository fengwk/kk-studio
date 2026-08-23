package fun.fengwk.kkstudio.platform.storage.service.model;

/**
 * 探针记录的权威媒体事实（blob 的不可变事实列）。
 *
 * <p>{@code width}/{@code height} 必须同时为 null 或同时为正数；{@code durationMs} 为 null 或正数。
 *
 * @param mediaType 权威媒体类型（非空白）
 * @param width 像素宽度（可为 null）
 * @param height 像素高度（可为 null）
 * @param durationMs 媒体时长毫秒（可为 null）
 */
public record StorageMediaFacts(String mediaType, Long width, Long height, Long durationMs) {

  public StorageMediaFacts {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    if ((width == null) != (height == null)) {
      throw new IllegalArgumentException("width and height must both be set or both be null");
    }
    if (width != null && (width <= 0 || height <= 0)) {
      throw new IllegalArgumentException("width and height must be positive when set");
    }
    if (durationMs != null && durationMs <= 0) {
      throw new IllegalArgumentException("durationMs must be positive when set");
    }
  }
}
