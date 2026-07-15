package fun.fengwk.kkstudio.core.harness.tool.configuration;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;

/** Supplies the single deployment-wide normalized Tool settings snapshot. */
public interface ToolSettingsProvider {
  ToolSettings get();
}
