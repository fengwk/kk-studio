package fun.fengwk.kkstudio.plugin.comfyui;

/** ComfyUI input 目录中的已上传媒体路径。 */
public record H3UploadedFile(String name, String subfolder, String type) {

  public H3UploadedFile {
    name = ComfyuiTexts.requireText(name, "name");
    subfolder = ComfyuiTexts.requireText(subfolder, "subfolder");
    type = ComfyuiTexts.requireText(type, "type");
    if (!"input".equals(type)) {
      throw new IllegalArgumentException("uploaded file type must be input");
    }
  }

  public String path() {
    return subfolder + "/" + name;
  }
}
