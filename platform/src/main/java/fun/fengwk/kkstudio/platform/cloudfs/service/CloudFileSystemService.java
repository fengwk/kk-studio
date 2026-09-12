package fun.fengwk.kkstudio.platform.cloudfs.service;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud File System 核心应用服务。
 *
 * <p>提供虚拟路径解析、目录/文本/BLOB 节点的 CRUD、CAS 版本控制以及与底座 StorageBlob 的引用联动。
 */
public interface CloudFileSystemService {

  /** 文本节点单个版本的最大 UTF-8 字节限制：1 MiB。更大内容应保存为 BLOB。 */
  int MAX_TEXT_BYTES = 1024 * 1024;

  /**
   * 根据虚拟绝对路径查找节点。
   *
   * @param path 目标路径
   * @return 节点 Optional；若不存在或中间路径非目录则返回 empty
   */
  Optional<CloudNode> findNode(CloudPath path);

  /**
   * 根据虚拟绝对路径获取节点（不存在时抛出 {@link
   * fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException}）。
   */
  CloudNode getNode(CloudPath path);

  /**
   * 列出指定目录下的直接子节点。根目录下精确隐藏系统目录 {@code .artifacts}，用户创建的 dot 文件及嵌套 dot 文件均正常返回。
   *
   * @param directoryPath 目录绝对路径
   * @return 直接子节点列表（按名称升序）
   */
  List<CloudNode> listChildren(CloudPath directoryPath);

  /**
   * 创建目录。
   *
   * @param path 目标目录路径（不可为根目录，不可位于 {@code /.artifacts} 下）
   * @param recursive 若为 true，自动递归创建缺失的父目录；若为 false，父目录不存在时报错
   * @return 创建（或已存在且允许递归时返回现存）的目录节点
   */
  CloudNode mkdir(CloudPath path, boolean recursive);

  /**
   * 写入文本文件（CAS 控制）。
   *
   * <ul>
   *   <li>{@code expectedRevision == 0}：创建新文件，允许在同一事务中递归创建缺失的父目录；若文件已存在则报错；
   *   <li>{@code expectedRevision > 0}：更新现有文件，要求当前版本必须恰好等于 expectedRevision；不创建文件或目录；
   * </ul>
   *
   * @param path 目标文件路径（不可位于 {@code /.artifacts} 下）
   * @param content 权威 UTF-8 文本内容（最大 1 MiB）
   * @param expectedRevision 预期的版本号
   * @return 新生成的当前活跃文本版本
   */
  CloudTextRevision writeText(CloudPath path, String content, long expectedRevision);

  /**
   * 精确编辑文本文件（CAS 控制）。
   *
   * <p>{@code expectedRevision} 必须大于 0。匹配 {@code oldString}：
   *
   * <ul>
   *   <li>匹配 0 次：抛出异常；
   *   <li>匹配多次且 {@code replaceAll == false}：抛出歧义异常；
   *   <li>匹配 1 次或 {@code replaceAll == true}：执行替换并生成版本 {@code expectedRevision + 1}。
   * </ul>
   *
   * @param path 目标文件路径
   * @param oldString 待替换的精确非空子串
   * @param newString 替换后的新子串
   * @param expectedRevision 预期的当前版本号（必须 > 0）
   * @param replaceAll 是否替换所有匹配项
   * @return 新生成的当前活跃文本版本
   */
  CloudTextRevision editText(
      CloudPath path,
      String oldString,
      String newString,
      long expectedRevision,
      boolean replaceAll);

  /**
   * 移动或重命名节点（CAS 控制）。
   *
   * @param sourcePath 源路径（不可为根，不可位于 {@code /.artifacts} 下）
   * @param targetPath 目标路径（不可为根，不可位于 {@code /.artifacts} 下，不可为源路径或其后代）
   * @param expectedVersion 源节点的预期元数据版本号
   * @return 移动后的节点
   */
  CloudNode moveNode(CloudPath sourcePath, CloudPath targetPath, long expectedVersion);

  /**
   * 删除节点（CAS 控制）。
   *
   * <ul>
   *   <li>根目录与 {@code /.artifacts} 禁止公开删除；
   *   <li>非空目录拒绝删除；
   *   <li>TEXT 节点删除时在同事务内显式删除所有文本版本历史；
   *   <li>BLOB 节点删除时在同事务内调用 {@code StorageBlobManager.release} 释放一份底层引用。
   * </ul>
   *
   * @param path 目标路径
   * @param expectedVersion 预期的元数据版本号
   */
  void deleteNode(CloudPath path, long expectedVersion);

  /**
   * 创建 BLOB 节点。
   *
   * <p>在事务内校验 blob 活跃状态、自动创建缺失父目录、插入 BLOB 节点并在同事务中执行 {@code StorageBlobManager.retain}。
   *
   * @param path 目标路径（不可位于 {@code /.artifacts} 下）
   * @param blobId 关联的 ACTIVE storage_blob UUID
   * @return 创建的 BLOB 节点
   */
  CloudNode createBlobNode(CloudPath path, UUID blobId);

  /**
   * 读取文本节点的当前活跃版本。
   *
   * @param path 文本文件绝对路径
   * @return 当前活跃文本版本
   */
  CloudTextRevision readCurrentText(CloudPath path);

  /**
   * 读取文本节点的指定历史版本。
   *
   * @param path 文本文件绝对路径
   * @param revision 版本号
   * @return 指定文本版本
   */
  CloudTextRevision readTextRevision(CloudPath path, long revision);
}
