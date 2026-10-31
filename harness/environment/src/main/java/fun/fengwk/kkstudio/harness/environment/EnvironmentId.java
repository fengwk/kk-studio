package fun.fengwk.kkstudio.harness.environment;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.Objects;
import java.util.UUID;

/**
 * Environment 的持久化路由身份：一个 UUID 值对象。
 *
 * <p>{@code EnvironmentId} 是 stable Environment 资源、Daemon envelope scope、Agent 工具路由与 harness work
 * 亲和性共用的唯一身份；display 名称只是元数据，绝不参与路由。canonical 表示是 {@link UUID#toString()} 的小写形式，解析时拒绝任何非 canonical
 * 文本。
 */
public record EnvironmentId(UUID value) {

  public EnvironmentId {
    Objects.requireNonNull(value, "value");
  }

  /** 解析 canonical UUID 文本；{@code null}、空白、大小写变体或非法形状都抛 {@link IllegalArgumentException}。 */
  public static EnvironmentId parse(String raw) {
    return new EnvironmentId(Uuids.parseCanonical(raw, "environmentId"));
  }

  /** 从已存在的 UUID 构造。 */
  public static EnvironmentId of(UUID value) {
    return new EnvironmentId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
