package fun.fengwk.kkstudio.ide.browser;

/** 精确意图查询回执；{@code matched=false} 是正常“不存在/已过期”，不是传输错误。 */
public record IdeBrowserUseIntentReply(boolean matched) {}
