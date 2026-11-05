package fun.fengwk.kkstudio.plugin.comfyui;

/** SaveVideo 输出描述符。 */
public record H3OutputDescriptor(String filename, String subfolder, String type) {

  public H3OutputDescriptor {
    filename = ComfyuiTexts.requireText(filename, "filename");
    subfolder = subfolder == null ? "" : subfolder;
    type = ComfyuiTexts.requireText(type, "type");
  }
}
