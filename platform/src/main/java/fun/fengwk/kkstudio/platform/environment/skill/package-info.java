/**
 * Platform 唯一的 Skill 来源配置、持久 inventory 与 READY 发布边界。
 *
 * <p>{@code environment_skill_source} 由本包独占写入：{@code version} 同时是行级 CAS 令牌与下发给 Daemon 的 {@code
 * sourceVersion}，不存在第二个配置代际。{@code environment_inventory.source_set_version} 只表达来源集合成员关系
 * （创建/删除来源）的代际；{@code environment_skill} 是每个来源最新一次成功扫描的持久 inventory， 正文永不入库。
 *
 * <p>并发约定：所有 mutating 事务按 {@code environment}（key share）→ {@code environment_inventory}（FOR
 * UPDATE）→ {@code environment_skill_source}（FOR UPDATE，source_id 序）的顺序取锁，因此来源 CRUD 与 READY
 * 发布短事务之间不会形成 锁环；读取路径不加锁，也不依赖内存状态。
 *
 * <p>只有被 READY 围栏（environment + ownerNodeId + leaseToken + READY + 未过期租约 + 集合代际相等）接受的最新报告才会 推进
 * inventory；旧报告与版本漂移快照一律整体忽略或整体拒绝，绝不覆盖更新的配置事实。
 */
package fun.fengwk.kkstudio.platform.environment.skill;
