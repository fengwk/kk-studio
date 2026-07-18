package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;

import java.util.Objects;

/** 解析全局 Tool settings；YOLO 由 Thread 传入。 */
@Component
public class ToolPolicyResolver {
  private final ToolSettingsProvider settingsProvider;

  public ToolPolicyResolver(ToolSettingsProvider settingsProvider) {
    this.settingsProvider = Objects.requireNonNull(settingsProvider, "settingsProvider");
  }

  public ResolvedPolicy resolve(boolean yoloEnabled) {
    return new ResolvedPolicy(settingsProvider.get(), yoloEnabled);
  }

  public record ResolvedPolicy(ToolSettings settings, boolean yoloEnabled) {}
}
