package fun.fengwk.kkstudio.plugin.comfyui;

/** ComfyUI 插件包内共享的文本校验与提取助手。 */
final class ComfyuiTexts {

  private ComfyuiTexts() {}

  static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
