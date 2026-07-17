package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.session.SessionYoloResolver;

import java.util.Objects;

/** Root Session 创建时从全局 settings 继承 defaultYolo。 */
@Component
public class DefaultYoloResolver implements SessionYoloResolver {
  private final ToolSettingsProvider settingsProvider;

  public DefaultYoloResolver(ToolSettingsProvider settingsProvider) {
    this.settingsProvider = Objects.requireNonNull(settingsProvider, "settingsProvider");
  }

  @Override
  public boolean defaultYolo() {
    return settingsProvider.get().defaultYolo();
  }
}
