package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;

import java.util.Objects;

/**
 * 一项 Mavis 能力的模型可见静态定义。
 *
 * <p>{@code name} 由 {@link MavisCapability#toolName()} 派生，因此能力 id、工具名与 schema 资源名只有一处事实源； {@code
 * inputSchema} 是模型参数契约，与网关 MCP endpoint 的线上 body 形状解耦：参数到 body 的映射由工具实现负责。
 */
public record MavisToolDefinition(
    MavisCapability capability, String name, String description, InputSchema inputSchema) {

  public MavisToolDefinition {
    capability = Objects.requireNonNull(capability, "capability");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
  }
}
