package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;

/**
 * 插件的 canonical durable 身份：小写 dotted/dashed 标识符（如 {@code core}、{@code com.example.goal}、 {@code
 * pi-base}），长度有界。插件 id 是自定义 Entry / CUSTOM_MESSAGE 元数据与 ownership 校验的稳定键。
 */
public record PluginId(String value) {

  /** 最大字符数，与其它 canonical 标识符一致。 */
  public static final int MAX_LENGTH = Identifiers.MAX_LENGTH;

  public PluginId {
    Objects.requireNonNull(value, "value");
    Identifiers.requireCanonical(value, "pluginId");
  }

  @Override
  public String toString() {
    return value;
  }
}
