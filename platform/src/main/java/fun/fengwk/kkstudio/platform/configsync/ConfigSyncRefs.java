package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

/** 配置同步引用构造工具，统一 Model 的 {@code providerName/modelName} 身份。 */
public final class ConfigSyncRefs {

  private ConfigSyncRefs() {}

  public static ConfigSyncRef ref(ConfigSyncKind kind, String name) {
    return new ConfigSyncRef(kind, name);
  }

  public static ConfigSyncRef model(String providerName, String modelName) {
    return ref(ConfigSyncKind.MODELS, modelName(providerName, modelName));
  }

  public static String modelName(String providerName, String modelName) {
    return providerName + "/" + modelName;
  }

  /** 该引用是否为给定 kind 下的指定 name。 */
  public static boolean matches(ConfigSyncRef ref, ConfigSyncKind kind, String name) {
    return ref != null && ref.getKind() == kind && name.equals(ref.getName());
  }
}
