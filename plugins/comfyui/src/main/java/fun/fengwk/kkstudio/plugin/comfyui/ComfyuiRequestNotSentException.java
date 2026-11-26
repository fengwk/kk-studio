package fun.fengwk.kkstudio.plugin.comfyui;

/** ComfyUI request was rejected locally before the HTTP client could send it. */
final class ComfyuiRequestNotSentException extends RuntimeException {

  ComfyuiRequestNotSentException(String message) {
    super(message);
  }
}
