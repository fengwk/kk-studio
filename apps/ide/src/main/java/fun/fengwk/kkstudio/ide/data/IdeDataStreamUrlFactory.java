package fun.fengwk.kkstudio.ide.data;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URI;

/**
 * 生成 DATA_BIND 下发的受控 Studio 同源数据入口。
 *
 * <p>实现必须只使用部署级受控 origin，不接受浏览器或 Daemon 提供的任意 host/port；返回的绝对 HTTP(S) URL 已携带一次性 ticket，或在查询中预留同名
 * ticket。应用层不硬编码 Web 路由。
 */
@FunctionalInterface
public interface IdeDataStreamUrlFactory {

  URI streamUrl(EnvironmentId environmentId, long generation, String ticket);
}
