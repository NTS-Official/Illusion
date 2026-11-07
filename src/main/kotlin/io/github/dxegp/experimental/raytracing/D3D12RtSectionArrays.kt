package io.github.dxegp.experimental.raytracing

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import java.util.WeakHashMap

object D3D12RtSectionArrays {
    private val sectionsField = ChunkAccess::class.java.getDeclaredField("sections").apply { isAccessible = true }
    private val escaped = WeakHashMap<Array<LevelChunkSection>, Boolean>()
    private val access = ThreadLocal<Access>()
    private var publisher: ((LevelChunk, Array<LevelChunkSection>) -> Unit)? = null

    class Access internal constructor(internal val chunk: ChunkAccess, internal val previous: Access?) : AutoCloseable {
        private val thread = Thread.currentThread()
        private var closed = false

        override fun close() {
            if (closed) return
            check(Thread.currentThread() === thread && access.get() === this)
            if (previous == null) access.remove() else access.set(previous)
            closed = true
        }
    }

    @JvmStatic
    fun borrow(chunk: ChunkAccess): Access = Access(chunk, access.get()).also(access::set)

    @JvmStatic
    fun expose(chunk: ChunkAccess, sections: Array<LevelChunkSection>): Array<LevelChunkSection> {
        if (access.get()?.chunk === chunk || chunk.javaClass != LevelChunk::class.java ||
            (chunk as LevelChunk).level.javaClass != ClientLevel::class.java) return sections
        val currentPublisher = synchronized(D3D12RtSectionArrays::class.java) {
            publisher ?: run {
                record(sections)
                return sections
            }
        }
        currentPublisher(chunk, sections)
        return sections
    }

    @Suppress("UNCHECKED_CAST")
    private fun current(chunk: ChunkAccess): Array<LevelChunkSection> = sectionsField.get(chunk) as Array<LevelChunkSection>

    internal fun section(chunk: ChunkAccess, index: Int): LevelChunkSection? = current(chunk).getOrNull(index)

    @JvmStatic
    @Synchronized
    internal fun available(chunk: ChunkAccess): Boolean = current(chunk) !in escaped

    @JvmStatic
    @Synchronized
    internal fun record(sections: Array<LevelChunkSection>): Boolean = escaped.put(sections, true) == null

    @JvmStatic
    @Synchronized
    internal fun install(next: (LevelChunk, Array<LevelChunkSection>) -> Unit) {
        publisher = next
    }
}
