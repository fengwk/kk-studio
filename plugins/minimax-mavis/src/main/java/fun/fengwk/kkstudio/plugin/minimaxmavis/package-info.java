/**
 * MiniMax Mavis 构建期插件对私有 Mavis 网关的协议客户端。
 *
 * <p>本包只负责与 Mavis 网关对接的协议事实：固定 CN/EN 官方 origin、deep-link 登录回调解析、JWT 本地时限、capability catalog、桌面
 * renewal 请求与签名、HTTP/业务状态分层校验，以及诊断信息去敏。它不依赖 Spring、数据库或 Platform，也不 决定工具权限、凭据持久化或媒体存储；这些由上层 Plugin
 * auto-configuration 与 Platform 服务承担，因此本包可以在无容器 环境的单元测试中直接驱动。
 */
package fun.fengwk.kkstudio.plugin.minimaxmavis;
