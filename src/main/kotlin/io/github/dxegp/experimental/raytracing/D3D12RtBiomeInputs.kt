package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.world.level.biome.Biome
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

object D3D12RtBiomeInputs {
    private fun field(name: String) = ModifiableBiomeInfo::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val originalInfo = field("originalBiomeInfo")
    private val modifiedInfo = field("modifiedBiomeInfo")
    private class Key(owner: ModifiableBiomeInfo, queue: ReferenceQueue<ModifiableBiomeInfo>? = null) :
        WeakReference<ModifiableBiomeInfo>(owner, queue) {
        private val identityHash = System.identityHashCode(owner)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }

    internal class Input(var version: Long = 0L, var active: Int = 0,
                         var failureRevision: Long = 0L, var failed: Boolean = false)
    internal class Mutation(val input: Input, val version: Long, val failureRevision: Long, val recover: Boolean)
    private val released = ReferenceQueue<ModifiableBiomeInfo>()
    private val inputs = hashMapOf<Key, Input>()

    private fun input(owner: ModifiableBiomeInfo): Input {
        while (true) inputs.remove(released.poll() ?: break)
        return inputs.getOrPut(Key(owner, released), ::Input)
    }

    internal fun owner(value: Any?): ModifiableBiomeInfo? = (value as? Biome)?.modifiableBiomeInfo()

    internal fun canonical(owner: ModifiableBiomeInfo): Boolean = owner.javaClass == ModifiableBiomeInfo::class.java

    internal fun same(owner: ModifiableBiomeInfo, value: ModifiableBiomeInfo.BiomeInfo?): Boolean = modifiedInfo.get(owner) === value

    internal fun available(owner: ModifiableBiomeInfo): Boolean = canonical(owner) &&
        (modifiedInfo.get(owner) ?: originalInfo.get(owner)) != null && input(owner).let { !it.failed && it.active == 0 }

    internal fun failed(owner: ModifiableBiomeInfo): Boolean = input(owner).failed

    internal fun begin(owner: ModifiableBiomeInfo): Mutation {
        val input = input(owner)
        val recover = input.active == 0
        input.active++
        input.version++
        return Mutation(input, input.version, input.failureRevision, recover)
    }

    internal fun finish(mutation: Mutation, completed: Boolean) {
        val input = mutation.input
        check(input.active > 0)
        if (!completed) {
            input.failureRevision++
            input.failed = true
        } else if (mutation.recover && input.active == 1 && input.version == mutation.version &&
            input.failureRevision == mutation.failureRevision) {
            input.failed = false
        }
        input.active--
        input.version++
    }

    @JvmStatic
    fun write(owner: ModifiableBiomeInfo, value: ModifiableBiomeInfo.BiomeInfo?, operation: Runnable) {
        if (canonical(owner)) D3D12SectionGeometry.publishBiomeInfo(owner, value, operation) else operation.run()
    }
}
