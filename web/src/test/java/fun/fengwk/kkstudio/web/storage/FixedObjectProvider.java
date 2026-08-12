package fun.fengwk.kkstudio.web.storage;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 测试用固定值 {@link ObjectProvider}：ObjectProvider 的全部方法都是 default（委托给内部 bean 容器）， Mockito 无法可靠
 * mock，因此测试用本实现固定返回单个值。
 */
public final class FixedObjectProvider<T> implements ObjectProvider<T> {

  private final T value;

  public FixedObjectProvider(T value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  @Override
  public T getObject() {
    return value;
  }

  @Override
  public T getObject(Object... args) {
    return value;
  }

  @Override
  public T getIfAvailable() {
    return value;
  }

  @Override
  public T getIfAvailable(Supplier<T> defaultSupplier) {
    return value;
  }

  @Override
  public void ifAvailable(Consumer<T> consumer) {
    consumer.accept(value);
  }

  @Override
  public T getIfUnique() {
    return value;
  }

  @Override
  public T getIfUnique(Supplier<T> defaultSupplier) {
    return value;
  }

  @Override
  public void ifUnique(Consumer<T> consumer) {
    consumer.accept(value);
  }

  @Override
  public Iterator<T> iterator() {
    return List.of(value).iterator();
  }

  @Override
  public Stream<T> stream() {
    return Stream.of(value);
  }

  @Override
  public Stream<T> orderedStream() {
    return Stream.of(value);
  }

  @Override
  public Stream<T> stream(Predicate<Class<?>> filter) {
    return Stream.of(value);
  }

  @Override
  public Stream<T> orderedStream(Predicate<Class<?>> filter) {
    return Stream.of(value);
  }

  @Override
  public Stream<T> stream(Predicate<Class<?>> filter, boolean includeNonSingletons) {
    return Stream.of(value);
  }

  @Override
  public Stream<T> orderedStream(Predicate<Class<?>> filter, boolean includeNonSingletons) {
    return Stream.of(value);
  }
}
