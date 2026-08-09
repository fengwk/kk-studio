package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Objects;

/** 可向模型声明并由执行器实现的工具描述。 */
public record ToolDescriptor(
    String name,
    String version,
    ToolType type,
    String description,
    String rendererKey,
    ToolParamsSchema inputSchema,
    ToolSideEffect sideEffect,
    Duration timeout) {

  public ToolDescriptor {
    if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
      throw new IllegalArgumentException(
          "name must start with a letter and contain only letters, digits, _ or -");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    type = Objects.requireNonNull(type, "type");
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    if (rendererKey == null || rendererKey.isBlank()) {
      throw new IllegalArgumentException("rendererKey must not be blank");
    }
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
  }
}
