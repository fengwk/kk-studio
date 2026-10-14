package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.Objects;

/**
 * 函数声明的输出计划槽位：资源类型与可选显式名称。
 *
 * <p>输出计划在 Run 启动时冻结：每个槽位分配一个 Resource ID 并写进 Run checkpoint，真正物化时才插入 Resource 与 {@code OUTPUT}
 * pin。{@code name} 为空表示由节点名与类型推导（单输出函数的常规情形）；显式名称用于多输出，避免同节点资源重名。
 */
public record CanvasFunctionOutputSpec(CanvasResourceKind kind, String name) {

  /** 单个函数的输出槽位上限。 */
  public static final int MAX_OUTPUTS = 8;

  /** 与 {@code canvas_resource.name} 一致的资源名长度上限。 */
  public static final int MAX_NAME_LENGTH = 512;

  public CanvasFunctionOutputSpec {
    Objects.requireNonNull(kind, "kind");
    if (name != null) {
      if (name.isBlank() || !name.equals(name.strip())) {
        throw new IllegalArgumentException(
            "output name must be non-blank without surrounding space");
      }
      if (name.length() > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException("output name must be at most " + MAX_NAME_LENGTH);
      }
    }
  }

  public static CanvasFunctionOutputSpec of(CanvasResourceKind kind) {
    return new CanvasFunctionOutputSpec(kind, null);
  }

  public static CanvasFunctionOutputSpec named(CanvasResourceKind kind, String name) {
    return new CanvasFunctionOutputSpec(kind, name);
  }

  /** 解析本次运行的资源名：显式名优先，否则用节点名加类型后缀。 */
  public String resolveName(String nodeName) {
    Objects.requireNonNull(nodeName, "nodeName");
    if (name != null) {
      return name;
    }
    String extension =
        switch (kind) {
          case IMAGE -> ".png";
          case VIDEO -> ".mp4";
          case AUDIO -> ".m4a";
          case TEXT -> ".txt";
        };
    int maxBaseLength = MAX_NAME_LENGTH - extension.length();
    String base =
        nodeName.length() <= maxBaseLength ? nodeName : nodeName.substring(0, maxBaseLength);
    return base + extension;
  }
}
