package fun.fengwk.kkstudio.chat;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Chat 模块的自动装配入口：把 Chat 领域 Bean、JdbcClient 仓库与 HTTP 控制器注册到宿主应用。
 *
 * <p>Chat 是建立在平台之上的应用：经平台的公开服务使用 Agent 目录、Environment 目录、Session 查询与深删除编排，并实现平台定义的 {@code
 * SessionOwnership}。{@code chat} 与 {@code chat_session} 两张表只由本模块的仓库访问。
 */
@ComponentScan(basePackageClasses = ChatAutoConfiguration.class)
@Configuration
public class ChatAutoConfiguration {}
