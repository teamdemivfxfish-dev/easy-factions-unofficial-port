package top.leonx.territory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.Builder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.Blocks;
import net.neoforged.neoforge.registries.DeferredRegister.Items;
import top.leonx.territory.blocks.TerritoryTableBlock;
import top.leonx.territory.blocks.TerritoryTableBlockEntity;
import top.leonx.territory.container.TerritoryTableMenu;
import top.leonx.territory.integration.FactionLifecycle;

@Mod("holdfast_factions")
public final class TerritoryMod {
   public static final String MODID = "holdfast_factions";
   public static final Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
   public static final Items ITEMS = DeferredRegister.createItems(MODID);
   public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
   public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
   public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
   public static final DeferredBlock<TerritoryTableBlock> TERRITORY_TABLE = BLOCKS.registerBlock(
      "territory_table", TerritoryTableBlock::new, TerritoryTableBlock.props()
   );
   public static final DeferredItem<BlockItem> TERRITORY_TABLE_ITEM = ITEMS.registerSimpleBlockItem("territory_table", TERRITORY_TABLE);
   public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
      "main",
      () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.territory"))
            .icon(() -> ((BlockItem)TERRITORY_TABLE_ITEM.get()).getDefaultInstance())
            .displayItems((params, output) -> output.accept((ItemLike)TERRITORY_TABLE_ITEM.get()))
            .build()
   );
   public static final DeferredHolder<MenuType<?>, MenuType<TerritoryTableMenu>> TERRITORY_MENU = MENUS.register(
      "territory_table", () -> IMenuTypeExtension.create((id, inv, buf) -> new TerritoryTableMenu(id, inv, buf))
   );
   public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TerritoryTableBlockEntity>> TERRITORY_BE = BLOCK_ENTITIES.register(
      "territory_table", () -> Builder.of(TerritoryTableBlockEntity::new, new Block[]{(Block)TERRITORY_TABLE.get()}).build(null)
   );

   public TerritoryMod(IEventBus modBus, ModContainer container) {
      BLOCKS.register(modBus);
      ITEMS.register(modBus);
      TABS.register(modBus);
      MENUS.register(modBus);
      BLOCK_ENTITIES.register(modBus);
      container.registerConfig(Type.SERVER, TerritoryConfig.SPEC, "territory-server.toml");
      modBus.addListener(FMLCommonSetupEvent.class, event -> FactionLifecycle.register());
   }
}
