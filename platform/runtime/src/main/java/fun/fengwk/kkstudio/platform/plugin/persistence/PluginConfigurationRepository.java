package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;

import java.util.List;
import java.util.Optional;

/**
 * Plugin catalog 与不可变普通配置历史的唯一权威仓储契约。
 *
 * <p>读取侧返回完整 catalog 记录与按 {@code (pluginId, revision)} 定位的不可变历史；写入侧只有一条 {@link #commit(long, List,
 * PluginCatalogManifest)} 路径，且必须在调用方已开启的真实事务中执行（{@code MANDATORY} 或显式事务校验）。
 *
 * <p>成功提交在同一事务内先用一条语句 CAS 全局 epoch 并新建该 epoch 的不可变 manifest 历史，再插入 changed 历史、校验 manifest 引用已存在且
 * code/schema/capability 身份匹配，最后发出 {@code pg_notify} 提示。CAS 未命中直接返回 false，绝不插入 manifest 历史或 changed
 * 历史、 也不发出通知；任何异常都必须由事务整体回滚，不提供绕过验证的写入口。
 */
public interface PluginConfigurationRepository {

  /** 读取完整权威 catalog 单行；行缺失时返回 {@code null}（由启动初始化/恢复明确失败，不默认兜底）。 */
  PluginCatalogRecord readCatalog();

  /**
   * 按全局 epoch 读取不可变完整 manifest 历史；该 epoch 无历史行时返回空。
   *
   * <p>这是「指定 epoch 的完整目录」的唯一读取入口，与 {@link #readCatalog()} 的当前指针对应 manifest 是不同职责，不是别名：它不做任何
   * current/default 兜底，缺失历史一律返回空，由调用方明确失败。
   *
   * @param epoch 目标全局 epoch，必须非负
   */
  Optional<PluginCatalogRecord> readCatalog(long epoch);

  /** 按 {@code (pluginId, revision)} 读取不可变配置历史；不存在时返回空。 */
  Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision);

  /**
   * 在调用方事务内做全局修订 CAS 并写入一次 complete manifest 与 changed 历史。
   *
   * @param expectedEpoch 期望的当前 epoch；成功时全局 epoch 前进到 {@code expectedEpoch + 1}
   * @param changedConfigurations 本次新增的配置历史，所有 revision 必须等于 {@code expectedEpoch + 1}
   * @param manifest 完整冻结 manifest，必须精确引用 changed 与已存在的历史行
   * @return 是否成功命中 CAS 并完成提交
   */
  boolean commit(
      long expectedEpoch,
      List<PluginConfigurationRow> changedConfigurations,
      PluginCatalogManifest manifest);
}
