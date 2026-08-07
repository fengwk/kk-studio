package fun.fengwk.kkstudio.core.ai.runtime.tool.configuration;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;

import java.util.Objects;

/** 直接规范化并暴露部署级 Tool settings。 */
public final class DeploymentToolSettingsProvider implements ToolSettingsProvider {
  private final ToolSettingsProperties properties;
  private final ToolSettingsCodec codec;

  public DeploymentToolSettingsProvider(
      ToolSettingsProperties properties, ToolSettingsCodec codec) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public ToolSettings get() {
    return codec.decode(codec.canonicalize(properties.getSettingsJson()));
  }
}
