package fun.fengwk.kkstudio.harness.runtime.extension;

/** 由应用显式提供的可信编译期 Harness 扩展。 */
public interface HarnessExtension {

  String id();

  int priority();

  void contribute(HarnessExtensionRegistry registry);
}
