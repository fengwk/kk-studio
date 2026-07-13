/**
 * 不可变语义 Session Entry Tree 与持久化端口。
 *
 * <p>Entry 只追加；Store 负责原子 leaf CAS，Context 只能从指定 leaf 的祖先链读取，绝不依赖运行 Delta。
 */
package fun.fengwk.kkstudio.harness.runtime.session;
