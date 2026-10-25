package com.zerochen.damageoverhaul.mixin;

import com.zerochen.damageoverhaul.event.ImpactEffectHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 眩晕期间强制终止苦力怕的自爆读条（冲击力/韧性系统）。
 *
 * 苦力怕的自爆由 Creeper.tick() 驱动，不经过 Mob.serverAiStep()，
 * 因此 MixinMobStunAI 的 AI 停用无法阻止自爆，必须单独处理：
 *   - isIgnited() 为 true 时每 tick 强制 setSwellDir(1)
 *   - swell 每 tick 累加 swellDir，达到 maxSwell 即 explodeCreeper()
 * 所以在 tick() 开头把 swell 归零、swellDir 置 -1，并清除 ignited 标记
 * （不清除 ignited 的话会被 tick() 重新置回 1，读条马上重启）。
 */
@Mixin(Creeper.class)
public abstract class MixinCreeperStun {

    @Shadow
    private int swell;

    @Shadow
    @Final
    private static EntityDataAccessor<Boolean> DATA_IS_IGNITED;

    @Inject(method = "tick", at = @At("HEAD"))
    private void damageoverhaul$cancelSwell(CallbackInfo ci) {
        Creeper creeper = (Creeper) (Object) this;
        CompoundTag tag = creeper.getPersistentData();
        if (!tag.contains(ImpactEffectHandler.STUN_END_TICK_KEY)) return;

        long endTick = tag.getLong(ImpactEffectHandler.STUN_END_TICK_KEY);
        if (creeper.level().getGameTime() >= endTick) return;

        // 眩晕中：中断自爆读条
        this.swell = 0;
        creeper.setSwellDir(-1);
        creeper.getEntityData().set(DATA_IS_IGNITED, false);
    }
}
