package fun.fengwk.kkstudio.core.studio.function.opencli;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter checkpoint map 的严格小型读写器。 */
final class OpenCliAdapterState {

  static final String UPLOADS = "uploads";
  static final String EXECUTION_ID = "executionId";
  static final String ASSET_ID = "assetId";
  static final String POLL_STARTED_AT = "pollStartedAt";

  private OpenCliAdapterState() {}

  static Map<String, Object> copy(Map<String, Object> source) {
    return new LinkedHashMap<>(source);
  }

  static String optionalString(Map<String, Object> state, String key) {
    Object value = state.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("adapterState." + key + " must be non-blank text");
    }
    return text;
  }

  static List<UploadedInput> uploads(Map<String, Object> state) {
    Object raw = state.get(UPLOADS);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("adapterState.uploads must be an array");
    }
    List<UploadedInput> uploads = new ArrayList<>();
    for (int index = 0; index < list.size(); index++) {
      Object item = list.get(index);
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("adapterState.uploads items must be objects");
      }
      Object resourceId = map.get("resourceId");
      Object resourcePath = map.get("resourcePath");
      if (!(resourceId instanceof String idText)
          || !idText.matches("[1-9][0-9]*")
          || !(resourcePath instanceof String path)
          || !isCanonicalResourcePath(path)) {
        throw new IllegalArgumentException(
            "adapterState.uploads contains an invalid resourceId/resourcePath");
      }
      long id;
      try {
        id = Long.parseLong(idText);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "adapterState.uploads resourceId is outside bigint range", exception);
      }
      uploads.add(new UploadedInput(id, path));
    }
    return List.copyOf(uploads);
  }

  static void putUploads(Map<String, Object> state, List<UploadedInput> uploads) {
    List<Map<String, Object>> encoded = new ArrayList<>();
    for (UploadedInput upload : uploads) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("resourceId", Long.toString(upload.resourceId()));
      item.put("resourcePath", upload.resourcePath());
      encoded.add(item);
    }
    state.put(UPLOADS, encoded);
  }

  private static boolean isCanonicalResourcePath(String path) {
    if (!path.matches("/resources/[A-Za-z0-9._~!$&'()+,;=:@%/-]+")) {
      return false;
    }
    try {
      URI parsed = URI.create(path);
      if (parsed.getQuery() != null
          || parsed.getFragment() != null
          || !parsed.normalize().getPath().startsWith("/resources/")) {
        return false;
      }
      for (String segment : parsed.getPath().split("/", -1)) {
        if (".".equals(segment) || "..".equals(segment)) {
          return false;
        }
      }
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  record UploadedInput(long resourceId, String resourcePath) {}
}
