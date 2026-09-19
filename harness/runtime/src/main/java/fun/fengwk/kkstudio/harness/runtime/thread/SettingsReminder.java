package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestResult;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;

import java.util.Objects;

/**
 * 把 SET_AGENT / SET_MODEL / SET_ENVIRONMENT 的实际变更渲染为 {@link SystemReminder} USER 消息。
 *
 * <p>提醒只描述生效后的当前事实；未改变 setting 的命令不产生变更，因此也不会产生提醒。
 */
public final class SettingsReminder {

  private SettingsReminder() {}

  /** 渲染一次生效的设置变更；{@code change.settings()} 已是变更后的完整快照。 */
  public static AgentMessage message(CommandHarvestResult.SettingsChange change) {
    Objects.requireNonNull(change, "change");
    BranchSettings settings = change.settings();
    ThreadCommandType type = change.type();
    return switch (type) {
      case SET_AGENT -> SystemReminder.message(
          "The agent for this branch is now `" + settings.agentName() + "`.");
      case SET_MODEL -> {
        ModelSelection model = settings.model();
        yield SystemReminder.message(
            "The model for this branch is now `"
                + model.providerName()
                + "/"
                + model.modelName()
                + "` (variant `"
                + model.variant()
                + "`).");
      }
      case SET_ENVIRONMENT -> settings.environmentName() == null
          ? SystemReminder.message("This branch is now detached from any environment.")
          : SystemReminder.message(
              "This branch is now attached to environment `" + settings.environmentName() + "`.");
      case USER_MESSAGE, CUSTOM_MESSAGE -> throw new IllegalArgumentException(
          "settings reminder requires a SET_* command type");
    };
  }
}
