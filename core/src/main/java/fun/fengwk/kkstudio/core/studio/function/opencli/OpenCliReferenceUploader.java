package fun.fengwk.kkstudio.core.studio.function.opencli;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按 frozen manifest 首见顺序流式上传，并把可恢复映射写入 checkpoint。 */
final class OpenCliReferenceUploader {

  private final OpenCliHubClient client;

  OpenCliReferenceUploader(OpenCliHubClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  UploadResult uploadAll(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      Map<String, Object> state) {
    List<OpenCliAdapterState.UploadedInput> uploads =
        new ArrayList<>(OpenCliAdapterState.uploads(state));
    if (uploads.size() > run.manifest().size()) {
      throw new IllegalArgumentException("checkpoint uploads exceed frozen manifest");
    }
    for (int index = 0; index < uploads.size(); index++) {
      if (!uploads.get(index).resourceId().equals(run.manifest().get(index).resourceId())) {
        throw new IllegalArgumentException("checkpoint uploads do not match frozen manifest order");
      }
    }
    for (int index = uploads.size(); index < run.manifest().size(); index++) {
      if (!context.isRunning()) {
        throw new IllegalStateException("Canvas Function run is no longer running");
      }
      CanvasFunctionFrozenReference reference = run.manifest().get(index);
      try (CanvasFunctionResourceStream original = context.openOriginal(reference)) {
        OpenCliHubClient.UploadedResource uploaded =
            client.upload(
                reference.name(), reference.mediaType(), original.size(), original.content());
        uploads.add(
            new OpenCliAdapterState.UploadedInput(reference.resourceId(), uploaded.resourcePath()));
      } catch (IOException exception) {
        throw new UncheckedIOException("failed to close frozen Canvas Resource stream", exception);
      }
      OpenCliAdapterState.putUploads(state, uploads);
      context.checkpoint("INPUT_UPLOADING", state);
    }
    OpenCliAdapterState.putUploads(state, uploads);
    context.checkpoint("INPUTS_UPLOADED", state);
    return new UploadResult(List.copyOf(uploads), state);
  }

  record UploadResult(List<OpenCliAdapterState.UploadedInput> uploads, Map<String, Object> state) {}
}
