package fun.fengwk.kkstudio.platform.studio.function.h3;

/** SaveVideo 输出描述符。 */
public record H3OutputDescriptor(String filename, String subfolder, String type) {

  public H3OutputDescriptor {
    filename = requireText(filename, "filename");
    subfolder = subfolder == null ? "" : subfolder;
    type = requireText(type, "type");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
