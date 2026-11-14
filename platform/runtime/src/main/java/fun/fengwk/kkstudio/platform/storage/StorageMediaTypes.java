package fun.fengwk.kkstudio.platform.storage;

/** Predicates for stored media types. */
public final class StorageMediaTypes {

  private StorageMediaTypes() {}

  /** Decides whether a stored media type has a generated preview. */
  public static boolean isPreviewable(String mediaType) {
    return mediaType != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"));
  }
}
