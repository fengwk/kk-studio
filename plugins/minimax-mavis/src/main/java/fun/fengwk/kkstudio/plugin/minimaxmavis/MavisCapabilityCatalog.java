package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.util.Set;

/**
 * 网关当前实际提供的 MCP endpoint 目录。
 *
 * <p>模型只看到 15 个静态 Tool，Tool 到 endpoint 的映射必须在使用前对齐一次线上目录：本类型保留这次校验的结果， {@link
 * #supports(MavisCapability)} 用于把稳定 Tool 映射到当前 provider endpoint，能力缺失时由调用方给出确定性错误。
 */
public record MavisCapabilityCatalog(Set<String> endpoints) {

  public MavisCapabilityCatalog {
    endpoints = Set.copyOf(endpoints);
  }

  /** 该能力对应的 endpoint 是否仍在线上目录中。 */
  public boolean supports(MavisCapability capability) {
    return endpoints.contains(capability.endpoint());
  }
}
