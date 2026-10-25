package com.zerochen.damageoverhaul.client;

import com.google.common.collect.Multimap;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.ArmorResistance;
import com.zerochen.damageoverhaul.config.ConfigLoader.ManaRepairEntry;
import com.zerochen.damageoverhaul.config.ConfigLoader.ProjectileConfig;
import com.zerochen.damageoverhaul.config.ConfigLoader.WeaponDistribution;
import com.zerochen.damageoverhaul.registry.ModComponents;
import com.zerochen.damageoverhaul.registry.ModEnchantments;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeProtectionEnchantment;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeProjectileStrikeEnchantment;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeStrikeEnchantment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品 tooltip 渲染：武器伤害分布 / 护甲抗性 / 射弹伤害分布 / 魔力修复信息。
 * 所有显示项都可通过 display_config.json 的 4 个开关独立控制。
 *
 * 仅在客户端加载（@OnlyIn），专用服务器不会加载此类（避免无意义的事件订阅和类加载）。
 * 显示数据优先使用服务端推送的 ConfigLoader 缓存（ModMessages 同步），避免客户端文件与服务器不一致。
 */
@OnlyIn(Dist.CLIENT)
public class TooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return;

        List<Component> tooltip = event.getToolTip();
        boolean addedSection = false;

        // 1. 武器伤害分布（开关：show_weapon_distribution）
        if (ConfigLoader.showWeaponDistribution()) {
            Map<String, Float> distribution = ModComponents.getDamageDistribution(stack);
            if (distribution == null || distribution.isEmpty()) {
                WeaponDistribution wd = ConfigLoader.getWeaponDistribution(itemId);
                if (wd != null) distribution = wd.damageDistribution;
            }

            if (distribution != null && !distribution.isEmpty()) {
                // 区分两种基数来源：
                // a) 物品有 ATTACK_DAMAGE 修饰器（剑/斧/弓等）→ 1.0+修正量
                // b) 无修饰器（枪械类模组武器）→ 解析其他模组添加的 tooltip 伤害行
                //   （ItemTooltipEvent 在所有 appendHoverText 之后触发，能读到"伤害: 10.0"/"Damage: 10"等行）
                float baseDamage;
                boolean hasAttr = hasMainhandAttackDamage(stack);
                if (hasAttr) {
                    baseDamage = getAttackDamage(stack);
                } else {
                    baseDamage = parseDamageFromTooltip(tooltip);
                }

                // 叠加武器 strike 附魔加成（不降低其他类型，直接增加对应占比）
                Map<String, Float> finalDist = new LinkedHashMap<>(distribution);
                Map<String, Float> strikeBonus = new LinkedHashMap<>();
                Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
                for (Map.Entry<String, RegistryObject<DamageTypeStrikeEnchantment>> encEntry
                        : ModEnchantments.STRIKE_ENCHANTMENTS.entrySet()) {
                    DamageTypeStrikeEnchantment ench = encEntry.getValue().orElse(null);
                    if (ench == null) continue;
                    int level = enchants.getOrDefault(ench, 0);
                    if (level <= 0) continue;
                    float bonus = ench.getRatioPerLevel() * level;
                    finalDist.merge(encEntry.getKey(), bonus, Float::sum);
                    strikeBonus.put(encEntry.getKey(), bonus);
                }
                boolean hasStrike = !strikeBonus.isEmpty();

                if (addedSection) tooltip.add(Component.empty());
                MutableComponent title = Component.translatable("tooltip.damageoverhaul.title")
                        .withStyle(ChatFormatting.GOLD);
                if (hasStrike) {
                    title = title.append(Component.translatable("tooltip.damageoverhaul.enchanted_suffix")
                            .withStyle(ChatFormatting.GREEN));
                }
                tooltip.add(title);

                List<Map.Entry<String, Float>> sorted = new ArrayList<>(finalDist.entrySet());
                sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

                for (Map.Entry<String, Float> entry : sorted) {
                    float ratio = entry.getValue();
                    if (ratio <= 0) continue;

                    String typeName = entry.getKey();
                    int percent = Math.round(ratio * 100);
                    Float bonus = strikeBonus.get(typeName);

                    MutableComponent line = Component.literal("  ")
                            .append(Component.translatable("damageoverhaul.type." + typeName)
                                    .withStyle(ChatFormatting.YELLOW));
                    if (baseDamage > 0) {
                        // 有基数（原版属性或解析tooltip文本）→ 显示 数值×占比
                        line = line.append(Component.literal(" "))
                                .append(Component.literal(String.format("%.1f", baseDamage * ratio))
                                        .withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" (" + percent + "%)")
                                        .withStyle(ChatFormatting.GRAY));
                    } else {
                        // 无基数（解析失败/未标注）→ 仅显示百分比
                        line = line.append(Component.literal(" (" + percent + "%)")
                                .withStyle(ChatFormatting.AQUA));
                    }
                    if (bonus != null && bonus > 0) {
                        MutableComponent bonusCmp = baseDamage > 0
                                ? Component.literal(" (+" + String.format("%.1f", baseDamage * bonus) + ")")
                                : Component.literal(" (+" + Math.round(bonus * 100) + "%)");
                        line = line.append(bonusCmp.withStyle(ChatFormatting.GREEN));
                    }
                    tooltip.add(line);
                }
                addedSection = true;
            }
        }

        // 2. 射弹伤害分布（开关：show_projectile_distribution）
        //    匹配规则：先查 projectile_item_mappings（如 tipped_arrow → minecraft:arrow），再兜底直接匹配
        if (ConfigLoader.showProjectileDistribution()) {
            // 先查映射表：物品ID → 射弹实体ID（同时获取 replaceEntity 标记）
            ConfigLoader.ProjectileItemMapping mapping = ConfigLoader.getProjectileMappingForItem(itemId);
            ResourceLocation projectileEntityId = (mapping != null) ? mapping.entityId : null;
            // 兜底：直接用物品ID作为实体ID
            if (projectileEntityId == null) {
                projectileEntityId = itemId;
            }
            ProjectileConfig projCfg = ConfigLoader.getAllProjectileConfigs().get(projectileEntityId);
            Map<String, Float> projDist = (projCfg != null) ? projCfg.damageDistribution
                    : ConfigLoader.getProjectileDistribution(projectileEntityId);

            // 当 replace_entity=true 时显示"射出: 实体名"提示
            if (mapping != null && mapping.replaceEntity
                    && !mapping.entityId.equals(itemId) && ConfigLoader.isAllowDangerousReplace()) {
                if (addedSection) tooltip.add(Component.empty());
                tooltip.add(Component.translatable("tooltip.damageoverhaul.projectile_replace",
                                mapping.entityId.toString())
                        .withStyle(ChatFormatting.RED));
                addedSection = true;
            }

            if (projDist != null && !projDist.isEmpty()) {
                // 叠加 projectile_strike 附魔加成（配置化 bonus_levels）
                Map<String, Float> finalProjDist = new LinkedHashMap<>(projDist);
                Map<String, Float> projBonus = new LinkedHashMap<>();
                Map<Enchantment, Integer> projEnchants = EnchantmentHelper.getEnchantments(stack);
                Map<String, Float> bonusCfg = ConfigLoader.getProjectileStrikeBonusLevels();
                for (Map.Entry<String, RegistryObject<DamageTypeProjectileStrikeEnchantment>> encEntry
                        : ModEnchantments.PROJECTILE_STRIKE_ENCHANTMENTS.entrySet()) {
                    DamageTypeProjectileStrikeEnchantment ench = encEntry.getValue().orElse(null);
                    if (ench == null) continue;
                    int level = projEnchants.getOrDefault(ench, 0);
                    if (level <= 0) continue;
                    String lvStr = Integer.toString(level);
                    Float bonus = bonusCfg.get(lvStr);
                    if (bonus == null || bonus <= 0) continue;
                    finalProjDist.merge(encEntry.getKey(), bonus, Float::sum);
                    projBonus.put(encEntry.getKey(), bonus);
                }
                boolean hasProjStrike = !projBonus.isEmpty();

                boolean hasNonZero = false;
                for (Float v : finalProjDist.values()) {
                    if (v != null && v > 0f) { hasNonZero = true; break; }
                }
                if (hasNonZero) {
                    if (addedSection) tooltip.add(Component.empty());
                    MutableComponent projTitle = Component.translatable("tooltip.damageoverhaul.projectile_title")
                            .withStyle(ChatFormatting.LIGHT_PURPLE);
                    if (hasProjStrike) {
                        projTitle = projTitle.append(Component.translatable("tooltip.damageoverhaul.enchanted_suffix")
                                .withStyle(ChatFormatting.GREEN));
                    }
                    tooltip.add(projTitle);

                    List<Map.Entry<String, Float>> sorted = new ArrayList<>(finalProjDist.entrySet());
                    sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

                    for (Map.Entry<String, Float> entry : sorted) {
                        float ratio = entry.getValue();
                        if (ratio <= 0f) continue;

                        String typeName = entry.getKey();
                        int percent = Math.round(ratio * 100);
                        Float bonus = projBonus.get(typeName);

                        // 射弹物品通常没有 ATTACK_DAMAGE（弓拉满决定伤害），只显示比例
                        MutableComponent line = Component.literal("  ")
                                .append(Component.translatable("damageoverhaul.type." + typeName)
                                        .withStyle(ChatFormatting.YELLOW))
                                .append(Component.literal(" "))
                                .append(Component.literal(percent + "%")
                                        .withStyle(ChatFormatting.AQUA));
                        if (bonus != null && bonus > 0) {
                            line = line.append(Component.literal(" (+" + Math.round(bonus * 100) + "%)")
                                    .withStyle(ChatFormatting.GREEN));
                        }
                        tooltip.add(line);
                    }
                    addedSection = true;
                }
            }
        }

        // 3. 护甲抗性（开关：show_armor_resistance）
        if (ConfigLoader.showArmorResistance()) {
            ArmorResistance armor = ConfigLoader.getArmorResistance(itemId);
            if (armor != null && armor.resistances != null && !armor.resistances.isEmpty()) {
                // 叠加护甲 protect 附魔抗性（每级 +5%）
                Map<String, Float> finalResist = new LinkedHashMap<>(armor.resistances);
                Map<String, Float> protectBonus = new LinkedHashMap<>();
                Map<Enchantment, Integer> armorEnchants = EnchantmentHelper.getEnchantments(stack);
                for (Map.Entry<String, RegistryObject<DamageTypeProtectionEnchantment>> encEntry
                        : ModEnchantments.PROTECTION_ENCHANTMENTS.entrySet()) {
                    DamageTypeProtectionEnchantment ench = encEntry.getValue().orElse(null);
                    if (ench == null) continue;
                    int level = armorEnchants.getOrDefault(ench, 0);
                    if (level <= 0) continue;
                    float bonus = ench.getResistancePerLevel() * level;
                    finalResist.merge(encEntry.getKey(), bonus, Float::sum);
                    protectBonus.put(encEntry.getKey(), bonus);
                }
                boolean hasProtect = !protectBonus.isEmpty();

                boolean hasNonZero = false;
                for (Float v : finalResist.values()) {
                    if (v != null && v != 0f) { hasNonZero = true; break; }
                }
                if (hasNonZero) {
                    if (addedSection) tooltip.add(Component.empty());
                    MutableComponent armorTitle = Component.translatable("tooltip.damageoverhaul.armor_title")
                            .withStyle(ChatFormatting.AQUA);
                    if (hasProtect) {
                        armorTitle = armorTitle.append(Component.translatable("tooltip.damageoverhaul.enchanted_suffix")
                                .withStyle(ChatFormatting.GREEN));
                    }
                    tooltip.add(armorTitle);

                    List<Map.Entry<String, Float>> sorted = new ArrayList<>(finalResist.entrySet());
                    sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

                    for (Map.Entry<String, Float> entry : sorted) {
                        float resist = entry.getValue();
                        if (resist == 0f) continue;

                        String typeName = entry.getKey();
                        ChatFormatting color = resist > 0 ? ChatFormatting.RED : ChatFormatting.GREEN;
                        String sign = resist > 0 ? "+" : "";
                        Float bonus = protectBonus.get(typeName);

                        MutableComponent line = Component.literal("  ")
                                .append(Component.translatable("damageoverhaul.type." + typeName)
                                        .withStyle(ChatFormatting.YELLOW))
                                .append(Component.literal(" ")
                                        .withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(sign + String.format("%.1f%%", resist))
                                        .withStyle(color));
                        if (bonus != null && bonus != 0f) {
                            String bSign = bonus > 0 ? "+" : "";
                            line = line.append(Component.literal(" (" + bSign + String.format("%.1f%%", bonus) + ")")
                                    .withStyle(ChatFormatting.GREEN));
                        }
                        tooltip.add(line);
                    }
                    addedSection = true;
                }
            }
        }

        // 4. 魔力修复信息（开关：show_mana_repair）
        if (ConfigLoader.showManaRepair()) {
            ManaRepairEntry manaEntry = ConfigLoader.getManaRepairEntry(itemId);
            if (manaEntry != null) {
                if (addedSection) tooltip.add(Component.empty());
                String statusKey = manaEntry.enabled ? "tooltip.damageoverhaul.mana_repair.enabled" : "tooltip.damageoverhaul.mana_repair.disabled";
                ChatFormatting statusColor = manaEntry.enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY;
                tooltip.add(Component.translatable("tooltip.damageoverhaul.mana_repair.title")
                        .withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(" ")
                                .append(Component.translatable(statusKey).withStyle(statusColor))));
                if (manaEntry.enabled) {
                    tooltip.add(Component.literal("  ")
                            .append(Component.translatable("tooltip.damageoverhaul.mana_repair.cost", manaEntry.manaPerDurability)
                                    .withStyle(ChatFormatting.GRAY)));
                }
            }
        }

        // 5. 第三方饰品加成（开关：show_external_charm_bonus）
        //    物品ID 在 external_charms.json 中配置的其他模组饰品，显示其混合分布的增伤/抗性
        if (ConfigLoader.showExternalCharmBonus()) {
            ConfigLoader.ExternalCharmConfig ext = ConfigLoader.getExternalCharmConfig(itemId.toString());
            if (ext != null && (!ext.attackBonuses.isEmpty() || !ext.defenseResistances.isEmpty())) {
                if (addedSection) tooltip.add(Component.empty());
                tooltip.add(Component.translatable("tooltip.damageoverhaul.external_charm.title")
                        .withStyle(ChatFormatting.GOLD));

                // 伤害增幅行（按数值降序，与武器/护甲 tooltip 排序规则一致；仅显示实际生效的正值）
                List<Map.Entry<String, Float>> atkSorted = new ArrayList<>(ext.attackBonuses.entrySet());
                atkSorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
                boolean atkFirst = true;
                for (Map.Entry<String, Float> entry : atkSorted) {
                    if (entry.getValue() <= 0f) continue;
                    MutableComponent line = Component.literal("  ")
                            .append(Component.translatable(atkFirst
                                            ? "tooltip.damageoverhaul.external_charm.attack"
                                            : "tooltip.damageoverhaul.external_charm.attack_cont")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(": "))
                            .append(Component.translatable("damageoverhaul.type." + entry.getKey())
                                    .withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(" +" + Math.round(entry.getValue() * 100) + "%")
                                    .withStyle(ChatFormatting.RED));
                    tooltip.add(line);
                    atkFirst = false;
                }

                // 类型抗性行（按数值降序）
                List<Map.Entry<String, Float>> defSorted = new ArrayList<>(ext.defenseResistances.entrySet());
                defSorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
                boolean defFirst = true;
                for (Map.Entry<String, Float> entry : defSorted) {
                    if (entry.getValue() <= 0f) continue;
                    MutableComponent line = Component.literal("  ")
                            .append(Component.translatable(defFirst
                                            ? "tooltip.damageoverhaul.external_charm.defense"
                                            : "tooltip.damageoverhaul.external_charm.defense_cont")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(": "))
                            .append(Component.translatable("damageoverhaul.type." + entry.getKey())
                                    .withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(" +" + Math.round(entry.getValue() * 100) + "%")
                                    .withStyle(ChatFormatting.GREEN));
                    tooltip.add(line);
                    defFirst = false;
                }

                // 叠加规则提示（与自家饰品一致）
                tooltip.add(Component.translatable("tooltip.damageoverhaul.charm.stack_rule")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                addedSection = true;
            }
        }

        // 6. 装备固有能力补正（黑魂式「力量：S」；开关：show_equipment_scaling）
        //    评级只看装备自身倍率（该能力在全部伤害类型的倍率和），与持有者属性点无关；
        //    属性系统完全未启用（无 more_attributes 且内部系统禁用）时不显示。
        if (ConfigLoader.showEquipmentScaling()
                && com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isScalingActive()) {
            if (ConfigLoader.isWeaponScalingEnabled()) {
                ConfigLoader.ScalingRow weaponRow = ConfigLoader.getWeaponScalingRow(itemId.toString());
                if (weaponRow != null) {
                    addedSection |= appendAbilityGradeSection(tooltip, addedSection, weaponRow, true);
                }
            }
            if (ConfigLoader.isArmorScalingEnabled()) {
                ConfigLoader.ScalingRow armorRow = ConfigLoader.getArmorScalingRow(itemId.toString());
                if (armorRow != null) {
                    addedSection |= appendAbilityGradeSection(tooltip, addedSection, armorRow, false);
                }
            }
        }

        // 7. 武器削韧值与冲击效果（开关：show_weapon_guard_break）
        //    显示武器自身的 guardBreak（削韧值）及关联的 impact_effect 名称（若有）。
        if (ConfigLoader.showWeaponGuardBreak()) {
            ConfigLoader.WeaponGuardBreak wgb = ConfigLoader.getWeaponGuardBreak(itemId.toString());
            if (wgb != null && (wgb.guardBreak > 0 || wgb.impactEffect != null)) {
                if (addedSection) tooltip.add(Component.empty());
                tooltip.add(Component.translatable("tooltip.damageoverhaul.guard_break.title")
                        .withStyle(ChatFormatting.GOLD));

                if (wgb.guardBreak > 0) {
                    tooltip.add(Component.literal("  ")
                            .append(Component.translatable("tooltip.damageoverhaul.guard_break.value")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(": " + String.format("%.1f", wgb.guardBreak))
                                    .withStyle(ChatFormatting.RED)));
                }

                if (wgb.impactEffect != null && !wgb.impactEffect.isEmpty()) {
                    ConfigLoader.ImpactEffect eff = ConfigLoader.getImpactEffect(wgb.impactEffect);
                    MutableComponent effLine = Component.literal("  ")
                            .append(Component.translatable("tooltip.damageoverhaul.guard_break.effect")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(": ")
                                    .withStyle(ChatFormatting.GRAY));
                    if (eff != null) {
                        // 显示效果 ID + 三轴方向向量 + 距离/眩晕时长
                        String dirStr = String.format("U:%.1f L:%.1f F:%.1f",
                                eff.upDown, eff.leftRight, eff.forwardBackward);
                        effLine.append(Component.literal(wgb.impactEffect)
                                        .withStyle(ChatFormatting.YELLOW))
                                .append(Component.literal(" (")
                                        .withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(dirStr)
                                        .withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(", ")
                                        .withStyle(ChatFormatting.GRAY))
                                .append(Component.translatable("tooltip.damageoverhaul.guard_break.distance",
                                                String.format("%.1f", eff.distance))
                                        .withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(", ")
                                        .withStyle(ChatFormatting.GRAY))
                                .append(Component.translatable("tooltip.damageoverhaul.guard_break.stun",
                                                eff.durationTick)
                                        .withStyle(ChatFormatting.AQUA))
                                .append(Component.literal(")")
                                        .withStyle(ChatFormatting.GRAY));
                    } else {
                        // 效果未配置/未找到，仅显示 ID
                        effLine.append(Component.literal(wgb.impactEffect)
                                .withStyle(ChatFormatting.YELLOW));
                    }
                    tooltip.add(effLine);
                }
                addedSection = true;
            }
        }

        // 8. 护甲韧性（开关：show_armor_toughness）
        //    显示护甲自身提供的 toughness（韧性）。
        if (ConfigLoader.showArmorToughness()) {
            ConfigLoader.ArmorToughness at = ConfigLoader.getArmorToughness(itemId.toString());
            if (at != null && at.toughness > 0) {
                if (addedSection) tooltip.add(Component.empty());
                tooltip.add(Component.translatable("tooltip.damageoverhaul.toughness.title")
                        .withStyle(ChatFormatting.AQUA));

                tooltip.add(Component.literal("  ")
                        .append(Component.translatable("tooltip.damageoverhaul.toughness.value")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(": " + String.format("%.1f", at.toughness))
                                .withStyle(ChatFormatting.GREEN)));
                addedSection = true;
            }
        }
    }

    /** 单条能力徽章：能力名 + 字母等级（按等级着色加粗） */
    private static MutableComponent abilityGradeChip(String attr, String grade) {
        return Component.translatable("attribute.damageoverhaul." + attr)
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(": " + grade)
                        .withStyle(gradeColor(grade), ChatFormatting.BOLD));
    }

    /**
     * 追加装备固有能力补正段：标题 + 一行「力量: S  敏捷: D」式徽章（过宽自动换行）。
     * @return 是否实际追加了段落
     */
    private boolean appendAbilityGradeSection(List<Component> tooltip, boolean alreadyHasSection,
                                              ConfigLoader.ScalingRow row, boolean weapon) {
        LinkedHashMap<String, String> grades = ConfigLoader.getAbilityGrades(row, weapon);
        if (grades.isEmpty()) return false;

        if (alreadyHasSection) tooltip.add(Component.empty());
        tooltip.add(Component.translatable(weapon
                        ? "tooltip.damageoverhaul.scaling.weapon_title"
                        : "tooltip.damageoverhaul.scaling.armor_title")
                .withStyle(weapon ? ChatFormatting.GOLD : ChatFormatting.AQUA));

        // 按像素宽度把徽章排成若干行（每行上限 220px）
        int maxWidth = 220;
        MutableComponent line = Component.literal("  ");
        int used = net.minecraft.client.Minecraft.getInstance().font.width(line);
        boolean first = true;
        for (Map.Entry<String, String> e : grades.entrySet()) {
            MutableComponent chip = abilityGradeChip(e.getKey(), e.getValue());
            int chipW = net.minecraft.client.Minecraft.getInstance().font.width(chip);
            int gap = 14;
            if (!first && used + gap + chipW > maxWidth) {
                tooltip.add(line);
                line = Component.literal("  ");
                used = net.minecraft.client.Minecraft.getInstance().font.width(line);
            }
            if (!first) {
                line.append(Component.literal("   ").withStyle(ChatFormatting.GRAY));
                used += gap;
            }
            line.append(chip);
            used += chipW;
            first = false;
        }
        tooltip.add(line);
        return true;
    }

    /** 字母等级 → 颜色（S 最高 → E 最低） */
    private static ChatFormatting gradeColor(String grade) {
        switch (grade) {
            case "S": return ChatFormatting.GOLD;
            case "A": return ChatFormatting.DARK_RED;
            case "B": return ChatFormatting.YELLOW;
            case "C": return ChatFormatting.GREEN;
            case "D": return ChatFormatting.AQUA;
            default:  return ChatFormatting.GRAY;
        }
    }

    /** 获取物品的攻击伤害（含玩家基础 1.0） */
    private float getAttackDamage(ItemStack stack) {
        float damage = 1.0f;
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (AttributeModifier mod : mods.get(Attributes.ATTACK_DAMAGE)) {
            damage += (float) mod.getAmount();
        }
        return damage;
    }

    /**
     * 判断物品主手是否声明了 ATTACK_DAMAGE 属性修饰器。
     * 剑/斧/弓等原版及模组近战武器都有（即使 +0）；枪械类模组武器通常没有
     *（伤害由射弹结算，如 Scorched Guns 的 Gun JSON / TACZ 的枪械 JSON）。
     */
    private boolean hasMainhandAttackDamage(ItemStack stack) {
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        return !mods.get(Attributes.ATTACK_DAMAGE).isEmpty();
    }

    /** 匹配 tooltip 中的伤害基数数字（兼容中英文："伤害: 10.0" / "Damage: 10" / "Damage:10+2"） */
    private static final java.util.regex.Pattern TOOLTIP_DAMAGE_PATTERN =
            java.util.regex.Pattern.compile("(?:伤害|damage)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 从其他模组已添加的 tooltip 行中解析伤害基数（通用，不针对特定模组）。
     * 跳过近战伤害行（含"近战"/"melee"），优先返回普通伤害行；
     * 找不到普通伤害行时回退近战伤害值；完全未找到返回 -1。
     */
    private float parseDamageFromTooltip(List<Component> tooltip) {
        float fallback = -1f;
        for (Component line : tooltip) {
            String text = line.getString();
            if (text == null || text.isEmpty()) continue;
            String lower = text.toLowerCase();
            if (!lower.contains("damage") && !text.contains("伤害")) continue;
            java.util.regex.Matcher m = TOOLTIP_DAMAGE_PATTERN.matcher(text);
            if (!m.find()) continue;
            try {
                float v = Float.parseFloat(m.group(1));
                if (v > 0) {
                    boolean isMelee = lower.contains("melee") || text.contains("近战");
                    if (!isMelee) return v;
                    if (fallback < 0) fallback = v;
                }
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
