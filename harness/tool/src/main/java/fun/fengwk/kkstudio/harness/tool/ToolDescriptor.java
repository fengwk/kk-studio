package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可向模型声明并由执行器实现的工具描述。
 *
 * <p>{@code defaultTimeout} 是 definition 自己的唯一默认执行超时；{@link Duration#ZERO} 表示该工具没有执行 deadline。 工具
 * arguments 中的显式超时严格覆盖该默认值，两者不合并：有效超时 = 显式值 orElse definition 默认值。
 */
public record ToolDescriptor(
    String name,
    String description,
    String rendererKey,
    InputSchema inputSchema,
    ToolSideEffect sideEffect,
    Duration defaultTimeout) {

  /** 模型可见工具名的全局上限，与 {@code agent_definition.name}、{@code mcp_tool.model_name} 等持久化列一致。 */
  public static final int NAME_MAX_LENGTH = 64;

  private static final Pattern NAME_SYNTAX = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

  public ToolDescriptor {
    if (!isValidName(name)) {
      throw new IllegalArgumentException(
          "name must start with a letter, contain only letters, digits, _ or -, and be at most "
              + NAME_MAX_LENGTH
              + " characters");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    if (rendererKey == null || rendererKey.isBlank()) {
      throw new IllegalArgumentException("rendererKey must not be blank");
    }
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
    defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
    if (defaultTimeout.isNegative()) {
      throw new IllegalArgumentException("defaultTimeout must not be negative");
    }
  }

  /**
   * 判断文本是否为合法模型可见工具名。名称以字母开头，只含字母、数字、{@code _} 或 {@code -}，且不超过 {@link #NAME_MAX_LENGTH} 个字符；该名称是
   * Agent 配置、权限与运行时查询的唯一产品身份，因此该规则在 descriptor 构造与所有持久化 / 配置边界共享。
   */
  public static boolean isValidName(String name) {
    return name != null && name.length() <= NAME_MAX_LENGTH && NAME_SYNTAX.matcher(name).matches();
  }
}
