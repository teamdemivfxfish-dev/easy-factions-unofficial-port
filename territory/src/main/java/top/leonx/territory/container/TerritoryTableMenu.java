package top.leonx.territory.container;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;

import java.util.LinkedHashMap;
import java.util.Map;

public class TerritoryTableMenu extends AbstractContainerMenu {
   public static final int INPUT_SLOTS = 9;
   public static final int INPUT_X = 10;
   public static final int INPUT_Y = 78;
   public static final int INVENTORY_Y = 118;
   public static final int HOTBAR_Y = 176;
   public final BlockPos pos;
   public final Map<String, Integer> coinValues;
   public final int costPerChunk;
   private final SimpleContainer input = new SimpleContainer(INPUT_SLOTS);
   private boolean vaultOpen = false;

   public TerritoryTableMenu(int id, Inventory inv, BlockPos pos) {
      this(id, inv, pos, TerritoryConfig.upkeepValues(), TerritoryConfig.upkeepCostPerChunk());
   }

   public TerritoryTableMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
      this(id, inv, buf.readBlockPos(), readValues(buf), buf.readVarInt());
   }

   private TerritoryTableMenu(int id, Inventory inv, BlockPos pos, Map<String, Integer> coinValues, int costPerChunk) {
      super(TerritoryMod.TERRITORY_MENU.get(), id);
      this.pos = pos;
      this.coinValues = coinValues;
      this.costPerChunk = costPerChunk;

      for (int i = 0; i < INPUT_SLOTS; i++) {
         this.addSlot(new TerritoryTableMenu.CoinSlot(this.input, i, INPUT_X + i * 18, INPUT_Y));
      }

      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new TerritoryTableMenu.VaultSlot(inv, 9 + row * 9 + col, INPUT_X + col * 18, INVENTORY_Y + row * 18));
         }
      }

      for (int col = 0; col < 9; col++) {
         this.addSlot(new TerritoryTableMenu.VaultSlot(inv, col, INPUT_X + col * 18, HOTBAR_Y));
      }
   }

   public static void writeOpenData(RegistryFriendlyByteBuf buf, BlockPos pos) {
      buf.writeBlockPos(pos);
      Map<String, Integer> values = TerritoryConfig.upkeepValues();
      buf.writeVarInt(values.size());
      values.forEach((id, value) -> {
         buf.writeUtf(id);
         buf.writeVarInt(value);
      });
      buf.writeVarInt(TerritoryConfig.upkeepCostPerChunk());
   }

   private static Map<String, Integer> readValues(RegistryFriendlyByteBuf buf) {
      int n = buf.readVarInt();
      Map<String, Integer> values = new LinkedHashMap<>();

      for (int i = 0; i < n; i++) {
         values.put(buf.readUtf(), buf.readVarInt());
      }

      return values;
   }

   public Container inputContainer() {
      return this.input;
   }

   public void setVaultOpen(boolean open) {
      this.vaultOpen = open;
   }

   public int inputValue() {
      long sum = 0L;

      for (int i = 0; i < INPUT_SLOTS; i++) {
         ItemStack stack = this.input.getItem(i);
         sum += (long)stack.getCount() * this.coinValues.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 0);
      }

      return (int)Math.min(2147483647L, sum);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      Slot slot = this.slots.get(index);
      if (slot != null && slot.hasItem()) {
         ItemStack stack = slot.getItem();
         ItemStack original = stack.copy();
         if (index < INPUT_SLOTS) {
            if (!this.moveItemStackTo(stack, INPUT_SLOTS, this.slots.size(), true)) {
               return ItemStack.EMPTY;
            }
         } else if (!this.moveItemStackTo(stack, 0, INPUT_SLOTS, false)) {
            return ItemStack.EMPTY;
         }

         if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
         } else {
            slot.setChanged();
         }

         return original;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      super.removed(player);
      this.clearContainer(player, this.input);
   }

   public boolean stillValid(Player player) {
      return player.level().getBlockState(this.pos).is((Block)TerritoryMod.TERRITORY_TABLE.get())
         && player.distanceToSqr((double)this.pos.getX() + 0.5, (double)this.pos.getY() + 0.5, (double)this.pos.getZ() + 0.5) <= 64.0;
   }

   private class CoinSlot extends Slot {
      CoinSlot(Container container, int index, int x, int y) {
         super(container, index, x, y);
      }

      public boolean mayPlace(ItemStack stack) {
         return TerritoryTableMenu.this.coinValues.containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
      }

      public boolean isActive() {
         return TerritoryTableMenu.this.vaultOpen;
      }
   }

   private class VaultSlot extends Slot {
      VaultSlot(Container container, int index, int x, int y) {
         super(container, index, x, y);
      }

      public boolean isActive() {
         return TerritoryTableMenu.this.vaultOpen;
      }
   }
}
