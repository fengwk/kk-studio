package fun.fengwk.kkstudio.harness.daemon.coding;

/** 同时具备落盘与读取能力的 resource 存储边界；独立 daemon 使用 {@link LocalFileResourceStore}。 */
public interface ResourceStore extends ResourceSink, ResourceSource {}
