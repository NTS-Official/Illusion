package io.github.dxegp.experimental.raytracing

import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import java.lang.ref.WeakReference

object D3D12RtBlockTintInputs {
    private val regionLevel = RenderSectionRegion::class.java.getDeclaredField("level").apply { isAccessible = true }
    private val calculations = ThreadLocal<Calculation?>()

    private fun worldOf(level: BlockAndTintGetter): ClientLevel? = when (level.javaClass) {
        ClientLevel::class.java -> level as ClientLevel
        RenderSectionRegion::class.java -> regionLevel.get(level) as? ClientLevel
        else -> null
    }?.takeIf { it.javaClass == ClientLevel::class.java }

    class Input internal constructor(private val source: BlockTintSource, private val state: BlockState,
                                     private val position: BlockPos, world: Any?, private val epoch: Int,
                                     internal val tint: D3D12RtTintInputs.Proof?) {
        private val world = WeakReference(world)

        internal fun current(source: BlockTintSource, state: BlockState, level: BlockAndTintGetter,
                             position: BlockPos, world: Any?, epoch: Int): Boolean =
            this.source === source && this.state === state && (tint == null ||
                this.world.get() === world && this.epoch == epoch && this.position == position &&
                worldOf(level) === world && D3D12RtTintInputs.current(tint, world, epoch))
    }

    class Calculation internal constructor(internal val source: BlockTintSource, internal val state: BlockState,
                                           internal val position: BlockPos, internal val world: Any?,
                                           internal val epoch: Int, internal var available: Boolean,
                                           internal val previous: Calculation?) {
        internal var read: D3D12RtTintInputs.Proof? = null
        internal var readCount = 0
    }

    internal fun begin(source: BlockTintSource, state: BlockState, level: BlockAndTintGetter,
                       position: BlockPos, world: Any?, epoch: Int): Calculation {
        val dependent = D3D12RtCompilerInputs.tintResolver(source) != null
        val previous = calculations.get()
        previous?.available = false
        return Calculation(source, state, position.immutable(), world, epoch,
            D3D12RtCompilerInputs.supportsTintSource(source) && !D3D12RtTintInputs.hasRead() &&
                (!dependent || world != null && worldOf(level) === world), previous).also { calculations.set(it) }
    }

    internal fun record(read: D3D12RtTintInputs.Read, world: Any?, epoch: Int) {
        calculations.get()?.let { calculation ->
            if (++calculation.readCount == 1) calculation.read = D3D12RtTintInputs.proof(read, world, epoch)
            else calculation.available = false
        }
    }

    internal fun finish(calculation: Calculation, completed: Boolean, world: Any?, epoch: Int): Input? {
        check(calculations.get() === calculation)
        if (calculation.previous == null) calculations.remove() else calculations.set(calculation.previous)
        if (!completed || !calculation.available) return null
        val resolver = D3D12RtCompilerInputs.tintResolver(calculation.source)
        if (resolver == null) return if (calculation.readCount == 0)
            Input(calculation.source, calculation.state, calculation.position, null, 0, null) else null
        if (calculation.world !== world || calculation.epoch != epoch) return null
        val read = calculation.read ?: return null
        val position = if (D3D12RtCompilerInputs.lowerTintPosition(calculation.source)) {
            if (!calculation.state.hasProperty(DoublePlantBlock.HALF)) return null
            if (calculation.state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER)
                calculation.position.below() else calculation.position
        } else calculation.position
        if (D3D12RtTintInputs.resolver(read) !== resolver || !D3D12RtTintInputs.current(read, world, epoch) ||
            read.columnKey != ChunkPos.pack(position) || read.index != ((position.z and 15) shl 4 or (position.x and 15))) return null
        return Input(calculation.source, calculation.state, calculation.position, world, epoch, read)
    }
}
