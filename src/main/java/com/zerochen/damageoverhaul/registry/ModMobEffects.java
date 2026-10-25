package com.zerochen.damageoverhaul.registry;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 伤害类型药水效果注册（mob_effects_config.json 驱动数值）。
 *
 * 每种伤害类型注册 4 个 MobEffect（N 类型 × 4）：
 *   resist_<type>      抗性提升（有益）：受击时该类型抗性 +X%/级，加性并入附魔抗性池
 *   vulnerable_<type>  抗性降低（有害）：受击时该类型抗性 -X%/级
 *   amplify_<type>     伤害增幅（有益）：攻击时造成的该类型伤害 ×(1+X)（X=每级值×等级，加性并入饰品增伤乘区）
 *   weaken_<type>      伤害削弱（有害）：攻击时造成的该类型伤害反向降低
 *
 * 与附魔相同的"启动冻结"模式：效果列表在注册表冻结前由 damage_types.json 预读决定，
 * 新增伤害类型需要重启游戏才会生成对应的 4 个效果；数值则完全热重载。
 * 效果图标走原版约定：assets/damageoverhaul/textures/mob_effect/<注册名>.png（手绘贴图）。
 */
public class ModMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, DamageOverhaulMod.MOD_ID);

    public static final Map<String, RegistryObject<DamageTypeMobEffect>> RESISTANCE_UP_EFFECTS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<DamageTypeMobEffect>> RESISTANCE_DOWN_EFFECTS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<DamageTypeMobEffect>> DAMAGE_UP_EFFECTS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<DamageTypeMobEffect>> DAMAGE_DOWN_EFFECTS = new LinkedHashMap<>();

    /** 冲击力/韧性系统：眩晕效果（阻止移动和物品使用） */
    public static RegistryObject<MobEffect> STUN;

    public static void registerAll() {
        STUN = MOB_EFFECTS.register("stun", () -> new StunEffect());
        for (String type : ModEnchantments.getInitialDamageTypes()) {
            RESISTANCE_UP_EFFECTS.put(type, MOB_EFFECTS.register("resist_" + type,
                    () -> new DamageTypeMobEffect(type, DamageTypeMobEffect.Kind.RESIST_UP)));
            RESISTANCE_DOWN_EFFECTS.put(type, MOB_EFFECTS.register("vulnerable_" + type,
                    () -> new DamageTypeMobEffect(type, DamageTypeMobEffect.Kind.RESIST_DOWN)));
            DAMAGE_UP_EFFECTS.put(type, MOB_EFFECTS.register("amplify_" + type,
                    () -> new DamageTypeMobEffect(type, DamageTypeMobEffect.Kind.DAMAGE_UP)));
            DAMAGE_DOWN_EFFECTS.put(type, MOB_EFFECTS.register("weaken_" + type,
                    () -> new DamageTypeMobEffect(type, DamageTypeMobEffect.Kind.DAMAGE_DOWN)));
        }
    }

    public static void registerBus(IEventBus bus) {
        MOB_EFFECTS.register(bus);
    }

    public static class DamageTypeMobEffect extends MobEffect {
        public enum Kind {
            RESIST_UP(MobEffectCategory.BENEFICIAL, 0x99453A),   // 沿用原版抗性提升色系
            RESIST_DOWN(MobEffectCategory.HARMFUL, 0x484D48),    // 沿用原版虚弱色系
            DAMAGE_UP(MobEffectCategory.BENEFICIAL, 0x932423),   // 沿用原版力量色系
            DAMAGE_DOWN(MobEffectCategory.HARMFUL, 0x5A6C81);    // 沿用原版缓慢色系

            public final MobEffectCategory category;
            public final int color;

            Kind(MobEffectCategory category, int color) {
                this.category = category;
                this.color = color;
            }
        }

        public final String damageType;
        public final Kind kind;

        protected DamageTypeMobEffect(String damageType, Kind kind) {
            super(kind.category, kind.color);
            this.damageType = damageType;
            this.kind = kind;
        }
    }

    /**
     * 受击端：汇总实体身上所有"抗性提升/降低"效果，返回 类型 → 抗性修正百分比（可负）。
     * 数值 = 每级值 × (amplifier + 1)，与附魔抗性同一加性池（百分比单位，5.0 = 5%）。
     */
    public static Map<String, Float> getTypeResistanceModifier(LivingEntity entity) {
        if (entity == null || !ConfigLoader.isMobEffectsEnabled()) return Collections.emptyMap();
        Map<String, Float> total = null;
        for (Map.Entry<String, RegistryObject<DamageTypeMobEffect>> e : RESISTANCE_UP_EFFECTS.entrySet()) {
            MobEffectInstance inst = entity.getEffect(e.getValue().get());
            if (inst == null) continue;
            ConfigLoader.MobEffectValues v = ConfigLoader.getMobEffectValues(e.getKey());
            if (v == null) continue;
            if (total == null) total = new LinkedHashMap<>();
            total.merge(e.getKey(), v.resistUpPerLevel * (inst.getAmplifier() + 1), Float::sum);
        }
        for (Map.Entry<String, RegistryObject<DamageTypeMobEffect>> e : RESISTANCE_DOWN_EFFECTS.entrySet()) {
            MobEffectInstance inst = entity.getEffect(e.getValue().get());
            if (inst == null) continue;
            ConfigLoader.MobEffectValues v = ConfigLoader.getMobEffectValues(e.getKey());
            if (v == null) continue;
            if (total == null) total = new LinkedHashMap<>();
            total.merge(e.getKey(), -v.resistDownPerLevel * (inst.getAmplifier() + 1), Float::sum);
        }
        return total == null ? Collections.emptyMap() : total;
    }

    /**
     * 攻击端：汇总实体身上所有"伤害增幅/削弱"效果，返回 类型 → 伤害增减比例（可负，0.10 = +10%）。
     * 加性并入饰品增伤乘区：typeDamage × (1 + 饰品加成 + 本修正)。
     */
    public static Map<String, Float> getTypeDamageModifier(LivingEntity entity) {
        if (entity == null || !ConfigLoader.isMobEffectsEnabled()) return Collections.emptyMap();
        Map<String, Float> total = null;
        for (Map.Entry<String, RegistryObject<DamageTypeMobEffect>> e : DAMAGE_UP_EFFECTS.entrySet()) {
            MobEffectInstance inst = entity.getEffect(e.getValue().get());
            if (inst == null) continue;
            ConfigLoader.MobEffectValues v = ConfigLoader.getMobEffectValues(e.getKey());
            if (v == null) continue;
            if (total == null) total = new LinkedHashMap<>();
            total.merge(e.getKey(), v.damageUpPerLevel * (inst.getAmplifier() + 1), Float::sum);
        }
        for (Map.Entry<String, RegistryObject<DamageTypeMobEffect>> e : DAMAGE_DOWN_EFFECTS.entrySet()) {
            MobEffectInstance inst = entity.getEffect(e.getValue().get());
            if (inst == null) continue;
            ConfigLoader.MobEffectValues v = ConfigLoader.getMobEffectValues(e.getKey());
            if (v == null) continue;
            if (total == null) total = new LinkedHashMap<>();
            total.merge(e.getKey(), -v.damageDownPerLevel * (inst.getAmplifier() + 1), Float::sum);
        }
        return total == null ? Collections.emptyMap() : total;
    }

    /**
     * 眩晕效果：阻止移动和物品使用。
     * MobEffect 本身只负责标记，实际移动/物品使用阻止由 ImpactEffectHandler 的 TickEvent 实现。
     */
    public static class StunEffect extends MobEffect {
        protected StunEffect() {
            super(MobEffectCategory.HARMFUL, 0x8B4513); // 棕色（大地色系，表示僵硬/冻结）
        }

        /** 稍微减少移速，让玩家感觉到效果存在（实际阻止在 TickEvent 中处理） */
        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            // 在效果持续期间每 tick 将水平速度归零
            net.minecraft.world.phys.Vec3 motion = entity.getDeltaMovement();
            entity.setDeltaMovement(0, motion.y * 0.8, 0);
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return true; // 每 tick 都触发 applyEffectTick
        }
    }
}
