/**
 * RFC 3986 百分号编码：路径段与 query 参数值共用的唯一编码入口。
 *
 * <p>本包只依赖 JDK。{@link java.net.URLEncoder} 实现的是 form-urlencoded（空格写成 {@code +}），只在接收方同样按表单解码时才
 * 成立；路径段以及不保证表单解码的 query 位置必须使用本包的编码，得到的是接收方无论按 RFC 3986 还是表单解码都能还原原文的 ASCII 文本。
 */
package fun.fengwk.kkstudio.common.uri;
