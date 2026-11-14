package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.common.error.DomainException;

/**
 * 图片上传的客户端像素事实校验：只在 mediaType 是图片时允许出现，且五项事实必须齐全、自洽。
 *
 * <p>同一份判定同时服务用户直传（必须携带事实）与服务端 staging（事实由 complete 探针给出，这里只允许缺省），因此「是否必需」是入参而不是两个分支。
 */
final class StorageUploadImageMetadata {

  private StorageUploadImageMetadata() {}

  /**
   * 校验并归一化图片事实；非图片必须完全不携带事实，缺省时返回 {@code null}。
   *
   * @param requireImageMetadata 图片是否必须携带完整事实（用户直传为 true，服务端 staging 为 false）
   */
  static Facts validate(
      String mediaType,
      String format,
      Integer originalWidth,
      Integer originalHeight,
      Integer storedWidth,
      Integer storedHeight,
      boolean requireImageMetadata) {
    if (!mediaType.startsWith("image/")) {
      if (format != null
          || originalWidth != null
          || originalHeight != null
          || storedWidth != null
          || storedHeight != null) {
        throw DomainException.validation("format", "image metadata is only valid for images");
      }
      return null;
    }
    boolean anyImageMetadata =
        format != null
            || originalWidth != null
            || originalHeight != null
            || storedWidth != null
            || storedHeight != null;
    boolean completeImageMetadata =
        format != null
            && originalWidth != null
            && originalHeight != null
            && storedWidth != null
            && storedHeight != null;
    if (!anyImageMetadata) {
      if (requireImageMetadata) {
        throw DomainException.validation(
            "format", "image format and dimensions are required for image uploads");
      }
      return null;
    }
    if (!completeImageMetadata) {
      throw DomainException.validation(
          "format", "image format and dimensions must be provided together");
    }
    if (!format.matches("[a-z0-9][a-z0-9.+-]{0,31}") || !mediaType.equals("image/" + format)) {
      throw DomainException.validation("format", "format must match the stored image media type");
    }
    if (originalWidth <= 0 || originalHeight <= 0 || storedWidth <= 0 || storedHeight <= 0) {
      throw DomainException.validation("storedWidth", "image dimensions must be positive");
    }
    long originalPixels = (long) originalWidth * originalHeight;
    long storedPixels = (long) storedWidth * storedHeight;
    if (originalPixels > 40_000_000L || storedPixels > originalPixels) {
      throw DomainException.validation(
          "storedWidth", "image dimensions exceed the supported range");
    }
    return new Facts(format, originalWidth, originalHeight, storedWidth, storedHeight);
  }

  /** 已校验的图片事实：format 与四个像素维度齐全。 */
  record Facts(
      String format,
      Integer originalWidth,
      Integer originalHeight,
      Integer storedWidth,
      Integer storedHeight) {}
}
