package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.CharmConfig;
import com.zerochen.damageoverhaul.config.ConfigLoader.ExternalCharmConfig;
import com.zerochen.damageoverhaul.item.DynamicCharmItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 饰品效果应用：遍历玩家 Curios 槽位中的饰品，按规则计算增伤/抗性。
 *
 * 支持两类饰品（合并结算，同类取最高不叠加）：
 *   1. 自家饰品：DynamicCharmItem，按 charms.json 单类型增伤/抗性；
 *   2. 第三方饰品：任意其他模组物品，按 external_charms.json 的物品ID精确匹配，
 *      支持混合分布（一件物品同时提供多种伤害类型的增伤/抗性）。
 *
 * 叠加规则（用户选定）：同类（同 damage_type）多个饰品取最高值，不线性叠加。
 *   例：玩家戴两个火宝石（+15%、+10%）→ 火伤 +15%（取最高），不是 +25%。
 *
 * 接入点：DamageHandler 在伤害分布解析后、最终结算前调用本类。
 *   攻击端：玩家攻击时，遍历玩家饰品，按 damage_type 匹配，对分布中的对应类型伤害按增伤比例加成。
 *   防御端：玩家受击时，遍历玩家饰品，按 damage_type 匹配，返回各类型的最高抗性（按类型独立减免）。
 *
 * 性能：findCurios 的谓词已过滤为"自家饰品或已配置的第三方物品"，遍历数量极小，开销可接受。
 */
public class CharmEffectHandler {

    /**
     * 攻击端：返回玩家饰品对每种伤害类型的最高增伤比例。
     * 调用方在 damage 分布中按类型乘以 (1 + bonus)。
     *
     * @param attacker      攻击者玩家
     * @param damageTypes   本次伤害涉及的伤害类型集合（用于过滤，仅查询这些类型）
     * @return Map<damage_type, max_bonus>（如 {"fire": 0.15, "magic": 0.10}）
     */
    public static Map<String, Float> getAttackBonuses(Player attacker, Set<String> damageTypes) {
        Map<String, Float> result = new HashMap<>();
        try {
            List<SlotResult> slots = CuriosApi.getCuriosHelper().findCurios(attacker,
                    CharmEffectHandler::isCharmSource);
            for (SlotResult r : slots) {
                ItemStack stack = r.stack();
                if (stack.getItem() instanceof DynamicCharmItem item) {
                    // 自家饰品：单类型增伤
                    CharmConfig cfg = ConfigLoader.getCharmConfig(item.charmId);
                    if (cfg == null || !damageTypes.contains(cfg.damageType)) continue;
                    if (cfg.attackDamageBonus > 0) {
                        // 同类取最高（用户选定规则）
                        result.merge(cfg.damageType, cfg.attackDamageBonus, Math::max);
                    }
                } else {
                    // 第三方饰品：混合分布增伤
                    ExternalCharmConfig ext = ConfigLoader.getExternalCharmConfig(getItemId(stack));
                    if (ext == null) continue;
                    for (Map.Entry<String, Float> e : ext.attackBonuses.entrySet()) {
                        if (!damageTypes.contains(e.getKey())) continue;
                        if (e.getValue() > 0) {
                            result.merge(e.getKey(), e.getValue(), Math::max);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Curios API 调用失败（理论上不会，因为 mods.toml 强依赖 Curios），但保险捕获
            com.zerochen.damageoverhaul.DamageOverhaulMod.LOGGER.warn("[CharmEffect] 攻击端遍历 Curios 饰品失败: {}", t.getMessage());
        }
        return result;
    }

    /**
     * 防御端：返回各伤害类型的最高饰品抗性（按类型独立减免，仅对配置的类型生效）。
     * 同类不叠取最高（自家 + 第三方合并）。
     *
     * @param defender      受击玩家
     * @param damageTypes   本次伤害涉及的伤害类型集合
     * @return Map<damage_type, max_resistance>（如 0.10 = 该类型减伤 10%）
     */
    public static Map<String, Float> getDefenseResistances(Player defender, Set<String> damageTypes) {
        Map<String, Float> result = new HashMap<>();
        try {
            List<SlotResult> slots = CuriosApi.getCuriosHelper().findCurios(defender,
                    CharmEffectHandler::isCharmSource);
            for (SlotResult r : slots) {
                ItemStack stack = r.stack();
                if (stack.getItem() instanceof DynamicCharmItem item) {
                    // 自家饰品：单类型抗性
                    CharmConfig cfg = ConfigLoader.getCharmConfig(item.charmId);
                    if (cfg == null || !damageTypes.contains(cfg.damageType)) continue;
                    if (cfg.defenseResistance > 0) {
                        result.merge(cfg.damageType, cfg.defenseResistance, Math::max);
                    }
                } else {
                    // 第三方饰品：混合分布抗性
                    ExternalCharmConfig ext = ConfigLoader.getExternalCharmConfig(getItemId(stack));
                    if (ext == null) continue;
                    for (Map.Entry<String, Float> e : ext.defenseResistances.entrySet()) {
                        if (!damageTypes.contains(e.getKey())) continue;
                        if (e.getValue() > 0) {
                            result.merge(e.getKey(), e.getValue(), Math::max);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            com.zerochen.damageoverhaul.DamageOverhaulMod.LOGGER.warn("[CharmEffect] 防御端遍历 Curios 饰品失败: {}", t.getMessage());
        }
        return result;
    }

    /**
     * 冲击力/韧性系统：返回玩家所有饰品的韧性总和（加性叠加）。
     * 自家饰品和第三方饰品的 toughness 字段均参与累加。
     */
    public static float getTotalToughness(Player player) {
        if (player == null) return 0f;
        float total = 0f;
        try {
            List<SlotResult> slots = CuriosApi.getCuriosHelper().findCurios(player,
                    CharmEffectHandler::isCharmSource);
            for (SlotResult r : slots) {
                ItemStack stack = r.stack();
                if (stack.getItem() instanceof DynamicCharmItem item) {
                    CharmConfig cfg = ConfigLoader.getCharmConfig(item.charmId);
                    if (cfg != null) total += cfg.toughness;
                } else {
                    ExternalCharmConfig ext = ConfigLoader.getExternalCharmConfig(getItemId(stack));
                    if (ext != null) total += ext.toughness;
                }
            }
        } catch (Throwable t) {
            com.zerochen.damageoverhaul.DamageOverhaulMod.LOGGER.warn("[CharmEffect] 遍历 Curios 饰品韧性失败: {}", t.getMessage());
        }
        return total;
    }

    /** 判定 Curios 槽位中的物品是否可能提供饰品效果（自家饰品或已配置的第三方饰品） */
    private static boolean isCharmSource(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof DynamicCharmItem) return true;
        return ConfigLoader.getExternalCharmConfig(getItemId(stack)) != null;
    }

    /** 物品 → 注册ID字符串（modid:path），未注册时返回 null */
    private static String getItemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }
}
