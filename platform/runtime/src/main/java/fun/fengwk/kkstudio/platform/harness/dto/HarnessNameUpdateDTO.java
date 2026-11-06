package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/**
 * Session / Thread 重命名的严格 {@code {name}} 请求体（两 API 共用同一个 bean）。
 *
 * <p>wire 层只保证 {@code name} 是 JSON string（missing / 显式 null 均保留为 null，非字符串 JSON primitive 直接抛
 * {@code DomainException}）；{@code null} 与空白 / 超长等规范化语义由请求 mapper 与 Core 的 {@code Names}
 * 权威判定，绝不在此复制长度逻辑。未知字段经 {@link #rejectUnknownField} 拒绝。
 */
@Data
public class HarnessNameUpdateDTO {

  /** 目标显示名称：必填，经 Core 权威规范化（非空、单行、至多 256 个 Unicode 码点）。 */
  private String name;
}
