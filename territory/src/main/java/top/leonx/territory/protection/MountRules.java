package top.leonx.territory.protection;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MountRules {

    private MountRules() {
    }

    public static boolean isRideAttempt(Player player, Entity target, ItemStack held) {
        if (!(target instanceof Saddleable) || player == null || player.isSecondaryUseActive()) return false;
        return held == null || held.isEmpty() || !changesTheMount(held);
    }

    static boolean changesTheMount(ItemStack stack) {
        return stack.is(Items.SADDLE) || stack.getItem() instanceof AnimalArmorItem || stack.has(DataComponents.FOOD)
                || stack.is(Items.LEAD) || stack.is(Items.NAME_TAG);
    }
}
