package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public class ManaRepairHandler {

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!ConfigLoader.isManaRepairEnabled()) return;

        repairArmor(player);
    }

    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!ConfigLoader.isManaRepairEnabled()) return;

        repairWeapon(player);
    }

    /** 静态修复：在背包中定期吸收魔力回复耐久 */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;
        if (!ConfigLoader.isManaRepairEnabled()) return;
        if (!ConfigLoader.isStaticRepairEnabled()) return;

        Player player = event.player;
        int interval = ConfigLoader.getStaticRepairInterval();
        if (player.tickCount % interval != 0) return;

        int repairPerTick = ConfigLoader.getStaticRepairPerTick();
        if (repairPerTick <= 0) return;

        // 遍历背包所有物品
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.isDamaged()) continue;

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;

            ConfigLoader.ManaRepairEntry entry = ConfigLoader.getManaRepairEntry(itemId);
            if (entry == null || !entry.enabled) continue;

            // 尝试修复 repairPerTick 点耐久
            int manaNeeded = repairPerTick * entry.manaPerDurability;
            if (manaNeeded <= 0) continue;

            int manaAvailable = requestManaFromPlayer(player, manaNeeded);
            if (manaAvailable >= manaNeeded) {
                int damage = stack.getDamageValue();
                int actualRepaired = Math.min(repairPerTick, damage);
                stack.setDamageValue(damage - actualRepaired);
            }
        }
    }

    private void repairArmor(Player player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            ItemStack armor = player.getItemBySlot(slot);
            if (armor.isEmpty() || !armor.isDamaged()) continue;
            tryRepairWithMana(player, armor);
        }
    }

    private void repairWeapon(Player player) {
        ItemStack mainhand = player.getMainHandItem();
        if (!mainhand.isEmpty() && mainhand.isDamaged()) {
            tryRepairWithMana(player, mainhand);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.isDamaged()) {
            tryRepairWithMana(player, offhand);
        }
    }

    private void tryRepairWithMana(Player player, ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamaged()) return;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return;

        ConfigLoader.ManaRepairEntry entry = ConfigLoader.getManaRepairEntry(itemId);
        if (entry == null || !entry.enabled) return;

        int damage = stack.getDamageValue();
        int manaNeeded = damage * entry.manaPerDurability;
        if (manaNeeded <= 0) return;

        int manaAvailable = requestManaFromPlayer(player, manaNeeded);
        if (manaAvailable > 0) {
            int repaired = manaAvailable / entry.manaPerDurability;
            if (repaired > 0) {
                int actualRepaired = Math.min(repaired, damage);
                stack.setDamageValue(damage - actualRepaired);
            }
        }
    }

    /** 通过反射调用 Botania ManaItemHandler，避免硬依赖 */
    private int requestManaFromPlayer(Player player, int manaToGet) {
        if (!ModList.get().isLoaded("botania")) return 0;

        try {
            Class<?> handlerClass = Class.forName("vazkii.botania.api.mana.ManaItemHandler");
            Object handler = handlerClass.getMethod("instance").invoke(null);
            // requestManaExactForTool(ItemStack, Player, int, boolean) → boolean
            java.lang.reflect.Method exactMethod = handlerClass.getMethod(
                    "requestManaExactForTool", ItemStack.class, Player.class, int.class, boolean.class);
            ItemStack tool = player.getMainHandItem();
            Boolean exactResult = (Boolean) exactMethod.invoke(handler, tool, player, manaToGet, true);
            if (exactResult != null && exactResult) {
                return manaToGet;
            }
            // requestMana(ItemStack, Player, int, boolean) → int
            java.lang.reflect.Method manaMethod = handlerClass.getMethod(
                    "requestMana", ItemStack.class, Player.class, int.class, boolean.class);
            Object result = manaMethod.invoke(handler, tool, player, manaToGet, true);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.debug("Mana request failed: {}", e.getMessage());
            return 0;
        }
    }
}
