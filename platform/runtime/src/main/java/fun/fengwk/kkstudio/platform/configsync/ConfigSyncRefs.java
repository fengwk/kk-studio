package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

/** 配置同步引用构造工具：Model 使用全局逻辑 name。 */
public final class ConfigSyncRefs {

  private ConfigSyncRefs() {}

  public static ConfigSyncRef ref(ConfigSyncKind kind, String name) {
    return new ConfigSyncRef(kind, name);
  }

  public static ConfigSyncRef model(String modelName) {
    return ref(ConfigSyncKind.MODELS, modelName);
  }
}
