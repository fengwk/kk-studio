/**
 * 请求作用域的用户可见消息解析。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.i18n.StudioMessageService} 按请求 locale
 * 解析平台资源包中的错误文案；中文缺键回退英文， 两种资源都缺键时抛出 {@link
 * java.util.MissingResourceException}，不静默返回空文案。资源由本模块提供，Web 与产品模块的 HTTP 边界共享同一实现。
 */
package fun.fengwk.kkstudio.platform.i18n;
