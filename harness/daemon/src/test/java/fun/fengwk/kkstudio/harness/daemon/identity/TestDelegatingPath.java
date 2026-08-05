package fun.fengwk.kkstudio.harness.daemon.identity;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * 测试基座：包装真实路径的委托 {@link Path}，使 {@code Files.*} 通过自建 {@link FileSystemProvider} 路由，并可按需让 {@code
 * toRealPath()} 确定性失败。
 *
 * <p>用途：环境身份逻辑要求环境根必须是真实目录（{@code Files.isDirectory} 为真）且 {@code toRealPath} 成功；默认 provider 上
 * 无法确定性制造“isDirectory 通过但 toRealPath 失败”的竞态，本基座用代理路径注入该失败，验证失败关闭语义。所有未注入的
 * 方法委托给真实路径，路径返回结果会重新包装以保持同一 FileSystem。
 */
final class TestDelegatingPath {

  private TestDelegatingPath() {}

  /** 包装真实路径；{@code failToRealPath} 为 true 时 {@code toRealPath()} 抛 IOException。 */
  static Path wrap(Path real, boolean failToRealPath) {
    return wrap(real, new DelegateFileSystem(failToRealPath));
  }

  private static Path wrap(Path real, DelegateFileSystem fileSystem) {
    return (Path)
        Proxy.newProxyInstance(
            Path.class.getClassLoader(),
            new Class<?>[] {Path.class},
            new Handler(real, fileSystem));
  }

  /** 解开本基座包装的路径；非包装路径原样返回。 */
  private static Path unwrap(Path path) {
    if (Proxy.isProxyClass(path.getClass())
        && Proxy.getInvocationHandler(path) instanceof Handler handler) {
      return handler.real;
    }
    return path;
  }

  /** 委托 Path 代理：仅拦截 getFileSystem 与可选的 toRealPath 失败，其余全部委托真实路径。 */
  private static final class Handler implements InvocationHandler {
    private final Path real;
    private final DelegateFileSystem fileSystem;

    private Handler(Path real, DelegateFileSystem fileSystem) {
      this.real = real;
      this.fileSystem = fileSystem;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(real, args);
      }
      if (method.getName().equals("getFileSystem")) {
        return fileSystem;
      }
      if (method.getName().equals("toRealPath") && fileSystem.failToRealPath) {
        throw new IOException("simulated realpath failure");
      }
      Object result = method.invoke(real, args);
      return result instanceof Path path ? wrap(path, fileSystem) : result;
    }
  }

  /** 委托 FileSystem：provider 指向自建 provider，其余委托默认 FileSystem。 */
  private static final class DelegateFileSystem extends FileSystem {
    private final FileSystem real = FileSystems.getDefault();
    private final DelegateProvider provider = new DelegateProvider();
    private final boolean failToRealPath;

    private DelegateFileSystem(boolean failToRealPath) {
      this.failToRealPath = failToRealPath;
    }

    @Override
    public FileSystemProvider provider() {
      return provider;
    }

    @Override
    public void close() throws IOException {
      real.close();
    }

    @Override
    public boolean isOpen() {
      return real.isOpen();
    }

    @Override
    public boolean isReadOnly() {
      return real.isReadOnly();
    }

    @Override
    public String getSeparator() {
      return real.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
      return real.getRootDirectories();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
      return real.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
      return real.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
      return wrap(real.getPath(first, more), this);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
      return real.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
      return real.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
      return real.newWatchService();
    }
  }

  /** 委托 FileSystemProvider：全部操作解包后委托默认 provider。 */
  private static final class DelegateProvider extends FileSystemProvider {
    private final FileSystemProvider real = FileSystems.getDefault().provider();

    @Override
    public String getScheme() {
      return real.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
      return real.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
      return FileSystems.getDefault();
    }

    @Override
    public Path getPath(URI uri) {
      return wrap(real.getPath(uri), false);
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      return real.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
      return real.newDirectoryStream(unwrap(dir), filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      real.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      real.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
      real.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      real.move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path1, Path path2) throws IOException {
      return real.isSameFile(unwrap(path1), unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
      return real.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
      return real.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
      real.checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, LinkOption... options) {
      return real.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(
        Path path, Class<A> type, LinkOption... options) throws IOException {
      return real.readAttributes(unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
      return real.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
        throws IOException {
      real.setAttribute(unwrap(path), attribute, value, options);
    }
  }
}
