package fun.fengwk.kkstudio.harness.runtime.resource;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

/**
 * 宿主资源存储能力：内容寻址资源的写入与读取。
 *
 * <p>该能力属于基础设施能力，不参与 Agent Loop 正确性判定。实现必须保证内容寻址幂等（相同内容重复写入返回同一规范引用且不
 * 覆盖已有对象），并且不持有调用方传入的数组、不暴露内部数组。实现只理解自己发布并拥有/支持的规范引用，不同实现之间不承诺互读 （例如本地文件实现与未来的对象存储实现各自只认自己的
 * scheme）。非法输入抛 {@link IllegalArgumentException}，存储/IO 失败抛 {@link IllegalStateException}。
 */
public interface ResourceStore {

  /**
   * 存储 {@code content} 并返回规范引用。
   *
   * <p>{@code mediaType} 必填且必须是规范小写 type/subtype；{@code name} 是可选展示名，可为 null。相同内容重复写入是幂等
   * 的：返回同一规范引用，且不覆盖已有对象。调用方传入的数组不会被持有，返回后修改原数组不影响已存内容。
   */
  ResourceRef put(String mediaType, String name, byte[] content);

  /**
   * 无副作用地构造与 {@link #put} 对匹配内容返回的完全相同的规范引用（不写入、不读取任何内容）。
   *
   * <p>{@code size}/{@code sha256} 必须与将要写入的字节精确一致；实现走与 put 相同的输入校验路径，但绝不触碰存储。调用方用它
   * 先计划引用（含全部确定性校验）再逐个 put；put 对匹配字节返回的引用必须与此方法返回的引用相等，不等即存储契约违反。
   */
  ResourceRef reference(String mediaType, String name, long size, String sha256);

  /**
   * 读取 {@code resource} 引用的内容。
   *
   * <p>实现只读取自己发布且拥有/支持的规范引用（例如本地文件实现仅支持其根目录下的 file 引用），不承诺读取其他实现发布的引用，也 不承诺支持未来新增的 scheme；各实现以自己的
   * scheme 契约拒绝不支持的引用。返回的数组是独立拷贝，修改它不影响后续读取。
   */
  byte[] read(ResourceRef resource);
}
