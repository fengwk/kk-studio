package fun.fengwk.kkstudio.platform.harness.plugin;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;

import java.util.List;

/**
 * 提供启动期冻结的插件快照。
 *
 * <p>Platform 只依赖这个窄接口，不负责插件发现、目录读取或 classloader 生命周期。
 */
public interface HarnessPluginSource {

  /** 返回不可变的启动期插件快照。 */
  List<HarnessPlugin> plugins();
}
