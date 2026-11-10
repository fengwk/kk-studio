package fun.fengwk.kkstudio.platform.catalog.dto;

import java.util.Objects;

/**
 * 工具目录条目的提供者：内置与应用/插件取 contributor 身份与显示名，MCP 取所属服务名。
 *
 * @param kind 提供者种类
 * @param id contributor 身份；MCP 为服务名
 * @param name 显示名；MCP 为服务名
 */
public record ToolProviderDTO(ToolProviderKind kind, String id, String name) {

  public ToolProviderDTO {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
  }
}
