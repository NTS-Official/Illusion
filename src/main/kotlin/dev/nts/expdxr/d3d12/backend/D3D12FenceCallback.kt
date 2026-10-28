package dev.nts.expdxr.d3d12.backend

/**
 * 栅栏完成回调（native 侧 `mcdr_fence_callback_fn` 的 upcall）。
 *
 * - 在栅栏监视线程上调用，不在注册表锁内，所以回调里可以再次注册/注销。
 * - 每次注册最多触发一次；触发后句柄即被释放。
 * - completed == false 表示等待超时，不是错误。
 * - **不要抛异常**：upcall stub 让异常逃逸会直接终止 JVM。
 *
 * 签名必须是 `(value: Long, completed: Boolean)`，JVM 描述符 `(JZ)V` 才与 native 对齐；
 * 参数**不可**写成 `Long?` / `Boolean?`（描述符变了，回调静默失配）。
 */
fun interface D3D12FenceCallback {
    /** value = 已达成的栅栏值；completed = false 表示等待超时。 */
    fun onFenceComplete(value: Long, completed: Boolean)
}