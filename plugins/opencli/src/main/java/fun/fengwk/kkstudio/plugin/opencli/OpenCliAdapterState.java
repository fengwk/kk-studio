package fun.fengwk.kkstudio.plugin.opencli;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.Execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
          || !(resourcePath instanceof String path)
          || !OpenCliHubClient.isCanonicalResourcePath(path)) {
        throw new IllegalArgumentException(
            "adapterState.uploads contains an invalid resourceId/resourcePath");
      }
      UUID id = Uuids.parseCanonical(idText, "adapterState.uploads resourceId");
      uploads.add(new UploadedInput(id, path));
    }
    return List.copyOf(uploads);
  }

  static void putUploads(Map<String, Object> state, List<UploadedInput> uploads) {
    List<Map<String, Object>> encoded = new ArrayList<>();
    for (UploadedInput upload : uploads) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("resourceId", upload.resourceId().toString());
      item.put("resourcePath", upload.resourcePath());
      encoded.add(item);
    }
    state.put(UPLOADS, encoded);
  }

  static void requireCompleteUploads(CanvasFunctionFrozenRun run, List<UploadedInput> uploads) {
    if (uploads.size() != run.manifest().size()) {
      throw new IllegalArgumentException("checkpoint uploads do not cover frozen manifest");
    }
    for (int index = 0; index < uploads.size(); index++) {
      if (!uploads.get(index).resourceId().equals(run.manifest().get(index).resourceId())) {
        throw new IllegalArgumentException("checkpoint uploads do not match frozen manifest");
      }
    }
  }

  static Execution awaitTerminal(
      OpenCliHubClient client,
      CanvasFunctionExecutionContext context,
      String executionId,
      Duration maxWait,
      String errorPrefix) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    Execution execution = client.getExecution(executionId, 0);
    while (!execution.status().terminal()) {
      if (!context.isRunning()) {
        throw new IllegalStateException("Canvas Function run is no longer running");
      }
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0L) {
        throw new OpenCliHubException(errorPrefix + " Hub execution exceeded maximum wait");
      }
      int waitSeconds =
          (int) Math.max(1L, Math.min(120L, Duration.ofNanos(remainingNanos).toSeconds()));
      execution = client.getExecution(executionId, waitSeconds);
    }
    return execution;
  }

  record UploadedInput(UUID resourceId, String resourcePath) {}
}
