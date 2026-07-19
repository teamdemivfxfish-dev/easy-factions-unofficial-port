package top.leonx.territory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import top.leonx.territory.blocks.TerritoryTableBlock;
import top.leonx.territory.blocks.TerritoryTableBlockEntity;
import top.leonx.territory.container.TerritoryTableMenu;

/**
 * MineTerritory, originally by Leon (leon_mout) + cnlimiter for Forge 1.16.x, licensed GPLv3.
 * This is a GPLv3 revival rebuilt for NeoForge 1.21.1: the Territory Table block opens a map GUI to
 * claim chunks, but the banner-power territory logic is replaced by Easy Factions faction claims.
 *
 * This jar stays GPLv3 and is shipped on its own; it talks to Easy Factions (MPL-2.0) only through its
 * public API at runtime, so the two licenses never mix in one distributable.
 */
@Mod(TerritoryMod.MODID)
public final class TerritoryMod {

    public static final String MODID = "territory";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredBlock<TerritoryTableBlock> TERRITORY_TABLE =
            BLOCKS.registerBlock("territory_table", TerritoryTableBlock::new,
                    TerritoryTableBlock.props());

    public static final DeferredItem<BlockItem> TERRITORY_TABLE_ITEM =
            ITEMS.registerSimpleBlockItem("territory_table", TERRITORY_TABLE);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.territory"))
                    .icon(() -> TERRITORY_TABLE_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(TERRITORY_TABLE_ITEM.get()))
                    .build());

    public static final DeferredHolder<MenuType<?>, MenuType<TerritoryTableMenu>> TERRITORY_MENU =
            MENUS.register("territory_table", () -> IMenuTypeExtension.create(
                    (id, inv, buf) -> new TerritoryTableMenu(id, inv, buf.readBlockPos())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TerritoryTableBlockEntity>> TERRITORY_BE =
            BLOCK_ENTITIES.register("territory_table", () -> BlockEntityType.Builder.of(
                    TerritoryTableBlockEntity::new, TERRITORY_TABLE.get()).build(null));

    public TerritoryMod(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        MENUS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        // Buy Claims button config (territory-server.toml). Values are only ever read at use-time.
        container.registerConfig(ModConfig.Type.SERVER, TerritoryConfig.SPEC);
    }
}
