package com.zerochen.damageoverhaul.item;

import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.CharmConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 动态饰品物品：每个实例对应 charms.json 中的一条饰品配置。
 * 实现 ICurioItem 让 Curios API 自动为该物品的 ItemStack 附着 ICurio 能力，识别为可装备饰品。
 * 实际可装备槽位由 CuriosCharmDataPack 注入的 curios:charm 物品标签 + 玩家 charm 槽位分配决定。
 * tooltip 显示该饰品关联的伤害类型和效果数值（增伤/抗性）。
 */
public class DynamicCharmItem extends Item implements ICurioItem {

    public final String charmId;

    public DynamicCharmItem(Properties props, String charmId) {
        super(props);
        this.charmId = charmId;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CharmConfig cfg = ConfigLoader.getCharmConfig(charmId);
        if (cfg == null) {
            tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.unconfigured")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        // 关联伤害类型
        tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.type")
                .append(": " + cfg.damageType)
                .withStyle(ChatFormatting.AQUA));
        // 攻击端增伤
        if (cfg.attackDamageBonus != 0) {
            tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.attack")
                    .append(": +" + formatPercent(cfg.attackDamageBonus) + "%")
                    .withStyle(ChatFormatting.RED));
        }
        // 防御端抗性
        if (cfg.defenseResistance != 0) {
            tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.defense")
                    .append(": +" + formatPercent(cfg.defenseResistance) + "%")
                    .withStyle(ChatFormatting.GREEN));
        }
        // 固定增伤
        if (cfg.attackDamageFlat != 0) {
            tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.attack_flat")
                    .append(": +" + cfg.attackDamageFlat)
                    .withStyle(ChatFormatting.RED));
        }
        // 固定抗性
        if (cfg.defenseResistanceFlat != 0) {
            tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.defense_flat")
                    .append(": +" + cfg.defenseResistanceFlat)
                    .withStyle(ChatFormatting.GREEN));
        }
        // 叠加规则提示
        tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.stack_rule")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    private String formatPercent(float f) {
        return String.format("%.0f", f * 100);
    }

    // ===== ICurioItem 装备行为：允许任意时刻装备/卸下/右键装备 =====
    @Override public boolean canEquip(SlotContext ctx, ItemStack stack) { return true; }
    @Override public boolean canUnequip(SlotContext ctx, ItemStack stack) { return true; }
    @Override public boolean canEquipFromUse(SlotContext ctx, ItemStack stack) { return true; }
}
