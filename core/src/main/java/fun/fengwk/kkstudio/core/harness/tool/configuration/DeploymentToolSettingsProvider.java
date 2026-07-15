package fun.fengwk.kkstudio.core.harness.tool.configuration;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;
import java.util.Objects;

/** Normalizes and exposes deployment Tool settings directly. */
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
