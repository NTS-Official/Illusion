package dev.nts.expdxr.d3d12.backend

/**
 * 栅栏完成回调（P33）。
 *
 * <p>原实现是 JNI 侧唯一一个从 Java 传到 native 的对象引用：native 缓存 {@code JavaVM*}、
 * 用 {@code NewGlobalRef} 持有它，并在每次触发时做 {@code GetEnv} / {@code AttachCurrentThread}
 * 以及 {@code GetMethodID(cls, "onFenceComplete", "(JZ)V")} 字符串查找。
 *
 * <p>FFM 下只剩一个 {@code Linker.upcallStub} 加一个整数句柄：native 收到的是函数指针 +
 * {@code userHandle}，Java 侧用 {@code ConcurrentHashMap<Integer, CallbackEntry>} 还原到本接口。
 * 因此触发路径不再需要 attach 线程，也不再依赖方法签名字符串——改个方法名不会静默失效。
 *
 * <p>契约（与 native 侧 {@code mcdr_fence_callback_fn} 一一对应）：
 * <ul>
 *   <li>在栅栏监视线程上调用，<b>不在</b>注册表锁内，所以回调里可以再次注册或注销。</li>
 *   <li>每次注册最多触发一次；触发后句柄即被释放。</li>
 *   <li>{@code completed == false} 表示等待超时，不是错误。</li>
 *   <li><b>不要抛异常</b>：upcall stub 的目标方法一旦让异常逃逸，JVM 会直接终止。
 *       调用方（{@code MCDR_ABI.onFenceComplete}）已包了兜底 try/catch，但本接口的实现
 *       仍应自行捕获，以免异常被吞掉后再也看不到原因。</li>
 * </ul>
 *
 * Kotlin 侧签名固定为 `onFenceComplete(value: Long, completed: Boolean)`：JVM 描述符
 * 必须是 `(JZ)V` 才能与 native 侧 {@code mcdr_fence_callback_fn} 对齐，参数**不可**写成
 * 可空 `Long?` / `Boolean?`（那会变成 `(Ljava/lang/Long;Ljava/lang/Boolean;)V`，回调失配）。
 */
fun interface D3D12FenceCallback {
    /**
     * @param value     已达成的栅栏值
     * @param completed {@code true} = 栅栏已完成；{@code false} = 等待超时
     */
    fun onFenceComplete(value: Long, completed: Boolean)
}