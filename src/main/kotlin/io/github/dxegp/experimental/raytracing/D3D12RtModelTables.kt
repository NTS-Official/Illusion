package io.github.dxegp.experimental.raytracing

import java.util.IdentityHashMap

object D3D12RtModelTables {
    private val supportedTables = setOf(IdentityHashMap::class.java, HashMap::class.java,
        java.util.Map.of<Any, Any>().javaClass, java.util.Map.of<Any, Any>(Any(), Any()).javaClass)

    class Frozen<Key, Value> internal constructor(source: Map<Key, Value>) :
        Map<Key, Value> by java.util.Collections.unmodifiableMap(IdentityHashMap(source))

    @JvmStatic
    fun freeze(source: Map<*, *>): Map<*, *> = when {
        source is Frozen<*, *> -> source
        source.javaClass in supportedTables -> Frozen(source)
        else -> source
    }
}
