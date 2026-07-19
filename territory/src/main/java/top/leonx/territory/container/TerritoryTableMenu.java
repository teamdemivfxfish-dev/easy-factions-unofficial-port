package top.leonx.territory.container;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import top.leonx.territory.TerritoryMod;

/**
 * Menu for the Territory Table. Holds no item slots; it is just the server/client bridge that carries
 * the block position and (in later phases) the claim/faction state the screen needs. The actual claim
 * "brain" is Easy Factions, reached server-side; this menu only transports what the GUI shows and the
 * actions the buttons fire.
 */
public class TerritoryTableMenu extends AbstractContainerMenu {

    public final BlockPos pos;

    public TerritoryTableMenu(int id, Inventory inv, BlockPos pos) {
        super(TerritoryMod.TERRITORY_MENU.get(), id);
        this.pos = pos;
    }

    public TerritoryTableMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;   // no slots to shift-click
    }

    @Override
    public boolean stillValid(Player player) {
        // keep the GUI open only while the player is near the table
        return player.level().getBlockState(pos).is(TerritoryMod.TERRITORY_TABLE.get())
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
