package com.zerochen.damageoverhaul.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

/**
 * Mixin 注入 {@link Projectile} 类，添加「伤害分布覆盖」实例字段。
 *
 * <p>用途：当射弹物品上带有 {@code projectile_strike_<damage_type>} 附魔时，
 * 在 {@code EntityJoinLevelEvent}（发射刚进世界时）当场计算出
 * 最终完整伤害分布（基础分布 + 所有附魔 × bonus_levels），
 * 写入本字段，后续 {@code LivingHurtEvent} 伤害结算时直接读取该字段
 * 覆盖 {@code tryGetProjectileDistribution} 的结果，
 * 彻底避免「反射读取 getPickupItem / 背包扫描匹配 / 附魔重新解析」
 * 等易失效链路。</p>
 *
 * <p>说明：本 Mixin 没有修改原版任何已有方法 / 字段 —— 仅用 {@link Unique}
 * 往类里加新字段和 getter/setter。因此不需要 refmap / MixinGradle 的重映射
 * （FG reobf 环境下 100% 兼容，不会因字段名混淆而失效）。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   if (projectile instanceof MixinProjectileDamageDistribution accessor) {
 *       accessor.damageoverhaul$setDamageDistributionOverride(finalDist);
 *   }
 * }</pre>
 */
@Mixin(Projectile.class)
public abstract class MixinProjectileDamageDistribution {

    /**
     * 若此字段非 null / 非空，则表示该射弹实例有预计算的伤害分布覆盖，
     * 伤害结算时应直接使用该分布，不再走射弹实体ID对应的基础分布。
     */
    @Unique
    private Map<String, Float> damageoverhaul$damageDistributionOverride;

    @Unique
    public Map<String, Float> damageoverhaul$getDamageDistributionOverride() {
        return this.damageoverhaul$damageDistributionOverride;
    }

    @Unique
    public void damageoverhaul$setDamageDistributionOverride(Map<String, Float> override) {
        if (override == null || override.isEmpty()) {
            this.damageoverhaul$damageDistributionOverride = null;
            return;
        }
        // 保护性拷贝，避免外部后续修改影响实体上已存的分布
        this.damageoverhaul$damageDistributionOverride = new HashMap<>(override);
    }
}
