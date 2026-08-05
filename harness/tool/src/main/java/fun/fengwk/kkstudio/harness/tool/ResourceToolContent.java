package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;

/** 指向规范 Resource URI 的内容单元；引用校验在 {@link ResourceRef} 构造时完成。 */
public record ResourceToolContent(ResourceRef resource) implements ToolContent {

  public ResourceToolContent {
    resource = Objects.requireNonNull(resource, "resource");
  }
}
