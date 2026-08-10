package fun.fengwk.kkstudio.core.studio.function.h3;

/** ComfyUI input 目录中的已上传媒体路径。 */
public record H3UploadedFile(String name, String subfolder, String type) {

  public H3UploadedFile {
    name = requireText(name, "name");
    subfolder = requireText(subfolder, "subfolder");
    type = requireText(type, "type");
    if (!"input".equals(type)) {
      throw new IllegalArgumentException("uploaded file type must be input");
    }
  }

  public String path() {
    return subfolder + "/" + name;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
