package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

object D3D12RtBlockExtensions {
    class Input internal constructor(internal val registry: MutableMap<Any?, Any?>, internal val block: Block) {
        internal var current = true
    }

    private class InputReference(input: Input, queue: ReferenceQueue<Input>) : WeakReference<Input>(input, queue) {
        val block = input.block
    }

    private val registryField by lazy {
        Class.forName("net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager")
            .getDeclaredField("BLOCK_EXTENSIONS").apply { isAccessible = true }
    }

    private class Registry(private val backing: MutableMap<Any?, Any?>) : AbstractMutableMap<Any?, Any?>() {
        private val released = ReferenceQueue<Input>()
        private val inputs = java.util.IdentityHashMap<Block, InputReference>()

        private fun reap() {
            while (true) {
                val reference = released.poll() as? InputReference ?: return
                if (inputs[reference.block] === reference) inputs.remove(reference.block)
            }
        }

        fun input(block: Block): Input {
            reap()
            return inputs[block]?.get() ?: Input(this, block).also { inputs[block] = InputReference(it, released) }
        }

        private fun <Result> publish(key: Any?, value: Any?, present: Boolean, operation: () -> Result): Result =
            synchronized(D3D12SectionGeometry::class.java) {
                val next = if (present) value else IClientBlockExtensions.DEFAULT
                if (key !is Block || backing.getOrDefault(key, IClientBlockExtensions.DEFAULT) === next)
                    return@synchronized operation()
                reap()
                var result: Result? = null
                D3D12SectionGeometry.publishBlockExtension(key, Runnable {
                    inputs.remove(key)?.get()?.current = false
                    result = operation()
                })
                @Suppress("UNCHECKED_CAST")
                result as Result
            }

        override val size: Int get() = synchronized(D3D12SectionGeometry::class.java) { backing.size }
        override fun containsKey(key: Any?): Boolean = synchronized(D3D12SectionGeometry::class.java) { backing.containsKey(key) }
        override fun containsValue(value: Any?): Boolean = synchronized(D3D12SectionGeometry::class.java) { backing.containsValue(value) }
        override fun get(key: Any?): Any? = synchronized(D3D12SectionGeometry::class.java) { backing[key] }
        override fun getOrDefault(key: Any?, defaultValue: Any?): Any? =
            synchronized(D3D12SectionGeometry::class.java) { backing.getOrDefault(key, defaultValue) }
        override fun put(key: Any?, value: Any?): Any? = publish(key, value, true) { backing.put(key, value) }
        override fun remove(key: Any?): Any? = publish(key, null, false) { backing.remove(key) }
        override fun clear() {
            for (key in synchronized(D3D12SectionGeometry::class.java) { backing.keys.toList() }) remove(key)
        }

        override val entries: MutableSet<MutableMap.MutableEntry<Any?, Any?>> = object : AbstractMutableSet<MutableMap.MutableEntry<Any?, Any?>>() {
            override val size: Int get() = this@Registry.size
            override fun add(element: MutableMap.MutableEntry<Any?, Any?>): Boolean = throw UnsupportedOperationException()
            override fun clear() = this@Registry.clear()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<Any?, Any?>> {
                val iterator = synchronized(D3D12SectionGeometry::class.java) { backing.entries.iterator() }
                return object : MutableIterator<MutableMap.MutableEntry<Any?, Any?>> {
                    private var key: Any? = null
                    override fun hasNext(): Boolean = synchronized(D3D12SectionGeometry::class.java) { iterator.hasNext() }
                    override fun next(): MutableMap.MutableEntry<Any?, Any?> = synchronized(D3D12SectionGeometry::class.java) {
                        val entry = iterator.next()
                        key = entry.key
                        object : MutableMap.MutableEntry<Any?, Any?> {
                            override val key: Any? get() = synchronized(D3D12SectionGeometry::class.java) { entry.key }
                            override val value: Any? get() = synchronized(D3D12SectionGeometry::class.java) { entry.value }
                            override fun setValue(newValue: Any?): Any? = synchronized(D3D12SectionGeometry::class.java) {
                                publish(entry.key, newValue, true) { entry.setValue(newValue) }
                            }
                            override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> &&
                                key === other.key && value == other.value
                            override fun hashCode(): Int = System.identityHashCode(key) xor (value?.hashCode() ?: 0)
                            override fun toString(): String = "$key=$value"
                        }
                    }
                    override fun remove() { publish(key, null, false) { iterator.remove() } }
                }
            }
        }
    }

    @JvmStatic
    fun ownRegistry(source: MutableMap<Any?, Any?>): MutableMap<Any?, Any?> =
        if (source.javaClass == Reference2ObjectOpenHashMap::class.java && source.isEmpty())
            Registry(Reference2ObjectOpenHashMap()) else source

    internal fun capture(block: Block, extension: IClientBlockExtensions?): Input? {
        val registry = registryField.get(null) as? Registry ?: return null
        if (extension !== IClientBlockExtensions.DEFAULT || registry.getOrDefault(block, IClientBlockExtensions.DEFAULT) !== extension) return null
        return registry.input(block)
    }

    internal fun current(input: Input?, block: Block): Boolean = input != null && input.current && input.block === block &&
        registryField.get(null) === input.registry &&
        input.registry.getOrDefault(block, IClientBlockExtensions.DEFAULT) === IClientBlockExtensions.DEFAULT
}
