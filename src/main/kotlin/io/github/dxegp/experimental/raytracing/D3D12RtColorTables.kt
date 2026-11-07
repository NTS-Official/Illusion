package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.world.level.DryFoliageColor
import net.minecraft.world.level.FoliageColor
import net.minecraft.world.level.GrassColor
import java.lang.ref.WeakReference
import java.util.function.Consumer
import java.util.function.Supplier

object D3D12RtColorTables {
    internal const val COUNT = 3
    private val owners = listOf(GrassColor::class.java, FoliageColor::class.java, DryFoliageColor::class.java)
    private val fields = owners.map { it.getDeclaredField("pixels").apply { isAccessible = true } }
    private class Input(array: IntArray?) {
        var array = WeakReference(array)
        var owned = array != null
        var active = 0
        var version = 0L
        var failureRevision = 0L
        var failed = false
    }

    internal class Publication internal constructor(internal val index: Int, internal val version: Long,
                                                    internal val failureRevision: Long, internal val owned: Boolean)
    private class Reload(val owner: Class<*>, val array: IntArray?, val previous: Reload?)
    private val inputs = arrayOfNulls<Input>(COUNT)
    private val reloads = ThreadLocal<Reload?>()

    internal fun index(owner: Class<*>): Int = owners.indexOf(owner).also { require(it >= 0) }

    private fun input(index: Int): Input = inputs[index] ?: Input(fields[index].get(null) as IntArray?)
        .also { inputs[index] = it }

    internal fun available(index: Int, array: IntArray?): Boolean = input(index).let {
        array != null && it.owned && !it.failed && it.active == 0 && it.array.get() === array
    }

    internal fun available(mask: Int): Boolean = owners.indices.all { index ->
        mask and (1 shl index) == 0 || available(index, fields[index].get(null) as IntArray?)
    }

    internal fun begin(owner: Class<*>, array: IntArray?): Publication {
        val index = index(owner)
        val input = input(index)
        val reload = reloads.get()
        input.active++
        input.version++
        return Publication(index, input.version, input.failureRevision, reload?.owner === owner && reload.array === array)
    }

    internal fun finish(publication: Publication, array: IntArray?, completed: Boolean) {
        val input = input(publication.index)
        check(input.active > 0)
        val actual = fields[publication.index].get(null) as IntArray?
        val stable = completed && actual === array && input.active == 1 && input.version == publication.version &&
            input.failureRevision == publication.failureRevision
        input.array = WeakReference(actual)
        input.owned = stable && publication.owned && actual != null
        if (stable) input.failed = false else {
            input.failed = true
            input.failureRevision++
        }
        input.active--
        input.version++
    }

    @JvmStatic
    fun read(owner: Class<*>, operation: Supplier<IntArray?>): IntArray? = D3D12SectionGeometry.readColorTable(owner, operation)

    @JvmStatic
    fun write(owner: Class<*>, array: IntArray?, operation: Runnable) {
        D3D12SectionGeometry.publishColorTable(owner, array, operation)
    }

    @JvmStatic
    fun reload(owner: Class<*>, array: IntArray?, operation: Consumer<IntArray?>) {
        index(owner)
        val reload = Reload(owner, array?.copyOf(), reloads.get())
        reloads.set(reload)
        try { operation.accept(reload.array) }
        finally {
            if (reload.previous == null) reloads.remove() else reloads.set(reload.previous)
        }
    }
}
