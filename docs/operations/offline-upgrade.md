# 离线数据库升级

从旧的 DEV / MAIN 部署把业务数据离线转换进当前 V1 数据库，只由操作员在批准的停机窗口内手工执行。
转换逻辑与 CLI 一起打包在同一个 Fat JAR 里：它作为一次性独占进程运行，不启动 Web 服务与 Worker，
也不自动执行任何部署动作。

本文给出前置检查、受保护输入、实际步骤与切换回退边界。日常部署、首次安装与共享数据库重建见
[部署与运行](deployment.md)；转换后的数据库契约见 [Schema](../modules/schema.md)。

## 适用前提

只支持与已发布版本逐字节一致的旧 DEV / MAIN 数据库：

| 旧部署 | DDL 版本 |
| --- | --- |
| DEV 基线 | `8126841e15cc166fa65f1de90c7313598f10044e` |
| MAIN 基线 | `cb36799384c60f15119439276a8656efe303dc68` |
| 当前目标 V1 | `f990606a115736e1e0a0ee411000e77c1174ae3d` |

任何其它 schema、被人为改动的约束或未知的命名空间、表、视图、序列、独立类型、collation、operator、函数与扩展都会被拒绝，
不会做幂等迁移或就地修补。

运行环境必须满足：

- PostgreSQL 17、UTF-8 数据库、`origin` 复制角色，且目标会话真实可写。
- 执行转换的机器是 Linux 或 macOS：受保护输入依赖 POSIX 的 owner-only 权限模型，不支持该权限模型的
  文件系统会直接 fail closed。

## 前置条件

1. **停止写入**：停掉所有 App 节点与 Worker 的领取，等待在途操作收敛。CLI 自己会再次核验旧源已经真正
   排空：仍有可运行 Thread、模型/工具调用事实、Harness Work、Issue Work、排队命令、未结算 Join、
   未交付 task、active Issue Run、未结束的 Environment 更新、非终态 Canvas Function Run、任何凭据/连接/
   上传/集群路由 lease 都会拒绝。留在 UNKNOWN 或非终态的运行必须先人工收敛。
2. **备份**：全库 dump、S3 全部业务对象、当前 32 字节 Plugin 主密钥、旧发行目录（旧 JAR 与旧 Daemon）。
   这些是回退的唯一依据，转换过程不会替你保留任何一份。
3. **准备空目标**：由操作员新建一个专用于本次升级的全新空 PostgreSQL 17/UTF8 数据库。CLI 不会
   `CREATE DATABASE`、drop、truncate、reset、baseline 或 repair，也不会复用任何已有库。
4. **准备受保护输入**：配置 JSON 与主密钥文件必须是绝对路径的普通文件、不跟 symlink、属主可读且
   group/other 无任何权限（`0600`）。配置 JSON 上限 1 MiB，主密钥文件恰好 32 字节 raw AES-256。
   真实口令与密钥只出现在这些文件中，不接受命令行、环境变量或 inline 参数。

`prepare` 的配置只描述目标数据库：

```json
{"target": {"url": "jdbc:postgresql://<pg-host>:5432/<empty-db>", "username": "<user>", "password": "<password>"}}
```

`dry-run` 与 `apply` 的配置给出旧源与目标，并按需给出主密钥与显式静态密钥材料：

```json
{
  "source": {"url": "jdbc:postgresql://<old-pg-host>:5432/<old-db>", "username": "<user>", "password": "<password>"},
  "target": {"url": "jdbc:postgresql://<pg-host>:5432/<empty-db>", "username": "<user>", "password": "<password>"},
  "sourceKeyFile": "/absolute/path/source-master.key",
  "targetKeyFile": "/absolute/path/target-master.key",
  "staticSecrets": {"comfyui": {"apiKey": "<current-static-secret>"}}
}
```

两份主密钥互不回退：只给其中一份时另一份保持缺失，旧密文与新密文各自用对应密钥处理。显式给出的密钥
即使本次转换暂时用不到也必须有效。

## 步骤

三条命令都从同一个 Fat JAR 进入，URL 与口令只在配置文件里出现：

```bash
java -jar web/target/kk-studio-web-<version>.jar database-upgrade --help
java -jar web/target/kk-studio-web-<version>.jar database-upgrade prepare --config /absolute/path/prepare.json
java -jar web/target/kk-studio-web-<version>.jar database-upgrade dry-run --config /absolute/path/convert.json
java -jar web/target/kk-studio-web-<version>.jar database-upgrade apply --config /absolute/path/convert.json
```

1. **prepare 初始化空目标**。它先确认目标没有任何用户对象，再由真实 Flyway 应用唯一当前 V1 并写出
   迁移历史，最后用当前 schema、迁移历史与 pristine 形态守卫确认。成功只输出 `mode=prepare` 与
   `applied=true`。
   目标非空（例如已经初始化过或存在未知对象）时以 `error.stage=PREPARE` 拒绝，且不改变该库。
   prepare 失败后目标可能已经留下 Flyway 元数据或 DDL；不要自动重试或删除，人工检查后按需新建另一个
   空库重来。
2. **dry-run 验证转换**。它在真实读写路径上跑完同一次转换然后回滚，成功输出 `mode=dry-run`、
   `applied=false`、识别到的 `profile` 与逐表行数，目标仍是 pristine。
3. **apply 提交转换**。它使用同一份算法与同一份输入，成功输出 `mode=apply`、`applied=true` 与逐表行数、
   `retiredClusterNodeRows`、`rewrittenCredentials`、`installedStaticCredentials`、
   `rewrittenSkillTokens`。apply 不做覆盖、增量或重试：目标已提交时再次执行会以
   `error.stage=TARGET_PRISTINE` 拒绝。
4. **核验**。退出码为 0 且输出符合上述形态才算成功。任何失败只输出固定阶段、异常类型、
   `outcomeUncertain` 与安全建议；`outcomeUncertain=true` 表示目标事务是否生效无法确认，必须人工检查
   目标库的实际状态（迁移历史、逐表行数、目录 epoch）而不是盲目重试。

CLI 在读取任何密钥前先关闭进程内实际 classpath 后端的全部现存 logger、根日志与 JUL（`LoggingSystem=none`
也不会让它变成 no-op），因此第三方驱动、Flyway 与 Plugin 的诊断不会把连接信息或凭据打进终端；它自身也从不
输出连接串、路径、原始配置、SQL 或凭据。

## 切换与回退

- **切换**：转换成功后，用新的 App 发行物替换旧 App，指向新的 V1 数据库。新 App 运行在 Java 21；
  旧主机上的 Daemon 覆盖安装为同一发行版本（旧 Node 侧 Daemon 产物不再使用），再验收 Environment
  `READY`、Daemon 重连与关键业务操作。
- **保留旧库与备份**：旧源库在转换期间只被只读一致快照读取，CLI 不修改、不清空它。旧库、全库 dump、
  原始主密钥与 S3 对象都必须按保留策略保存，直到新部署验收完成。
- **回退**：需要撤销时回到被保留的旧发行物与旧数据库/备份，恢复原始 32 字节主密钥与 S3 对象。已经
  转换过的 V1 目标库不再是 pristine，不能再次作为转换目标；重新升级必须新建另一个空库。
- **S3**：转换不删除也不改写任何对象；对象始终受备份恢复策略保护。

## CLI 绝不做的事

不创建/删除/清空/重置/修复数据库，不自动重试，不改写旧源，不覆盖或合并已提交的目标，不启动 Web 或
Worker，不执行任何部署、切换或清理动作；这些都必须由操作员按本文与
[部署与运行](deployment.md) 手工完成。

开发与 CI 的隔离升级验收入口见 [开发与测试](development-and-testing.md#按意图选择检查)。
