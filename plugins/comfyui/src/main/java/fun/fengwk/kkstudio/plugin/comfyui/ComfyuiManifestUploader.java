package fun.fengwk.kkstudio.plugin.comfyui;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.io.IOException;
import java.util.UUID;

/** 把冻结 manifest 的引用逐个上传到 ComfyUI，并在每个产物产生后立即 checkpoint。 */
final class ComfyuiManifestUploader {

  private final StandardComfyuiClient client;

  ComfyuiManifestUploader(StandardComfyuiClient client) {
    this.client = client;
  }

  /** 逐个上传尚未上传的 frozen 引用；断点恢复只补未完成的部分。 */
  H3AdapterState upload(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest,
      H3AdapterState state) {
    for (H3ReferenceManifest.Item item : manifest.items()) {
      UUID resourceId = item.reference().resourceId();
      if (state.uploads().containsKey(resourceId)) {
        continue;
      }
      ComfyuiRunGuard.ensureRunning(context);
      try (CanvasFunctionResourceStream original = context.openOriginal(item.reference())) {
        H3UploadedFile uploaded =
            client.upload(
                resourceId + trustedExtension(item.reference()),
                item.reference().mediaType(),
                original.size(),
                original.content(),
                run.canvasId());
        state = state.withUpload(resourceId, uploaded);
        context.checkpoint(ComfyuiCanvasFunctionAdapter.COMFY_UPLOADING, state.encode());
      } catch (IOException error) {
        throw new IllegalStateException("cannot close frozen Resource stream", error);
      }
    }
    return state;
  }

  private static String trustedExtension(CanvasFunctionFrozenReference reference) {
    return switch (reference.mediaType().toLowerCase()) {
      case "image/jpeg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      case "image/heic" -> ".heic";
      case "image/heif" -> ".heif";
      case "video/mp4" -> ".mp4";
      case "video/quicktime" -> ".mov";
      case "audio/wav", "audio/x-wav" -> ".wav";
      case "audio/mpeg" -> ".mp3";
      default -> throw new IllegalArgumentException("unsupported ComfyUI mediaType");
    };
  }
}
