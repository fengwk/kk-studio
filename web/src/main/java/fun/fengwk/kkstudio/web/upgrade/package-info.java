/**
 * 一次性离线升级：把旧 DEV/MAIN 部署的持久数据读入当前平台契约，并给出唯一的进程入口。
 *
 * <p>它只在离线工具进程里被调用，绝不装配进常驻 Web 运行时，也不对外暴露平台 API。职责按调用顺序分为四层：
 *
 * <ul>
 *   <li>入口与编排：{@link fun.fengwk.kkstudio.web.upgrade.LegacyDatabaseUpgradeCli} 是 {@code
 *       database-upgrade prepare|dry-run|apply --config /absolute/path.json}
 *       的独占一次性进程边界，只输出固定事实、固定失败阶段与退出码，并在读取任何 secret 前关闭实际 classpath 后端的全部现存 logger、根日志与 JUL；Web Fat
 *       JAR 的唯一 main 在首参命中时路由到它，不启动 Spring Web/worker。 {@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacyUpgradeCliException} 是它面向运维的固定失败分类；
 *   <li>受保护输入：{@link fun.fengwk.kkstudio.web.upgrade.LegacyUpgradeConfig} 严格解析绝对路径、owner-only 的
 *       JSON 与 raw32 主密钥文件，得到连接信息、主密钥与显式静态材料；
 *   <li>显式空目标：{@link fun.fengwk.kkstudio.web.upgrade.LegacyDatabasePrepare} 只把操作员新建的空 PostgreSQL 17
 *       数据库用真实 Flyway 推进到当前唯一 V1，并由 {@link fun.fengwk.kkstudio.web.upgrade.LegacySchemaProfile} 与
 *       {@link fun.fengwk.kkstudio.web.upgrade.LegacyDatabaseUpgrade} 的 pristine 守卫复核；
 *   <li>转换内核：{@link fun.fengwk.kkstudio.web.upgrade.LegacyDatabaseUpgrade} 是唯一转换算法（源
 *       profile/drain、目标 pristine、逐表对账与密钥轮换），由 {@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacySourceGuard}、{@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacyTableCopy}、{@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacySystemSettings}、{@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacyPluginCredentials}、{@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacySkillTokens}、{@link
 *       fun.fengwk.kkstudio.web.upgrade.LegacyStaticCredentials} 与 {@link
 *       fun.fengwk.kkstudio.web.upgrade.OfflinePluginCatalog} 提供旧密文、settings、目录语义的读取与重封装。
 * </ul>
 *
 * <p>两条主密钥互不回退：只给其中一份时另一份保持缺失；显式提供的材料即使本次转换暂不需要也必须真实有效。任何失败都不输出连接、路径、SQL、字段值或凭据。
 */
package fun.fengwk.kkstudio.web.upgrade;
