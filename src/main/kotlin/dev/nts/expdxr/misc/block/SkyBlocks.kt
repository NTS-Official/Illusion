package dev.nts.expdxr.misc.block

import dev.nts.expdxr.Illusion.Companion.MODID
import java.util.function.Supplier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * 天空方块的注册入口。
 *
 * 方块属性抄原版玻璃：不可见但仍然是完整碰撞箱，`noOcclusion` 保证它不会挡住邻居面的可见性判断，
 * 也不会像石头那样在自己周围引入额外的面剔除。
 */
object SkyBlocks {
    private val BLOCKS = DeferredRegister.createBlocks(MODID)
    private val ITEMS = DeferredRegister.createItems(MODID)
    private val BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID)

    private val SKY_BLOCK_PROPERTIES = Supplier { BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS).noOcclusion() }

    val SKY_BLOCK = BLOCKS.registerBlock("sky_block", ::SkyBlock, SKY_BLOCK_PROPERTIES)

    val SKY_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(SKY_BLOCK)

    val SKY_BLOCK_ENTITY = BLOCK_ENTITIES.register("sky_block", Supplier { BlockEntityType(::SkyBlockEntity, SKY_BLOCK.get()) })

    /**
     * 注册到 mod 事件总线。
     *
     * 必须在 mod 构造期调用：`DeferredRegister` 的监听器要在注册表冻结之前挂上，
     * 创造模式物品栏的注入也是一个 mod 总线事件。
     */
    fun register(modEventBus: IEventBus) {
        BLOCKS.register(modEventBus)
        ITEMS.register(modEventBus)
        BLOCK_ENTITIES.register(modEventBus)
        modEventBus.addListener<BuildCreativeModeTabContentsEvent> { event ->
            if (event.tabKey == CreativeModeTabs.BUILDING_BLOCKS) {
                event.accept(SKY_BLOCK_ITEM.get())
            }
        }
    }
}
