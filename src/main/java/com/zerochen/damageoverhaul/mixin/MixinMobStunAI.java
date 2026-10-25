package com.zerochen.damageoverhaul.mixin;

import com.zerochen.damageoverhaul.event.ImpactEffectHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 眩晕期间停用 Mob AI 但保留物理（冲击力/韧性系统）。
 *
 * Mob.serverAiStep() 中 tick 的内容：sensing、goalSelector（行为）、targetSelector（索敌）、
 * navigation（寻路）、moveControl（移动）、lookControl（转头）、jumpControl（跳跃）。
 * 眩晕 NBT 标记存在时在方法开头 cancel —— 索敌、攻击、寻路、移动、转头全部暂停。
 *
 * 关键：LivingEntity.aiStep() 中的 travel()（重力/摩擦/击退速度结算）不受本 Mixin 影响，
 * 因此 applyDirectionalPush 施加的击退速度可以正常消费，生物会被击退并滑行。
 *
 * 不用 setNoAi(true) 的原因：NoAI 会连同 travel 一起冻结（击退速度永远不被消费、
 * 不受重力），导致冲击力生效时生物原地不动。
 */
@Mixin(Mob.class)
public abstract class MixinMobStunAI {

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void damageoverhaul$stunAI(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        CompoundTag tag = mob.getPersistentData();
        if (!tag.contains(ImpactEffectHandler.STUN_END_TICK_KEY)) return;

        long endTick = tag.getLong(ImpactEffectHandler.STUN_END_TICK_KEY);
        if (mob.level().getGameTime() >= endTick) {
            // 眩晕结束：清理标记，本方法不再 cancel，AI 正常恢复
            tag.remove(ImpactEffectHandler.STUN_END_TICK_KEY);
            return;
        }

        // 眩晕中：取消当前正在进行的行为（拉弓/持弩/使用物品/音爆等）。
        // 这些行为可能不依赖 AI（如物品使用计时器），且眩晕期间可能被重新触发，
        // 故需每 tick 重复取消。
        ImpactEffectHandler.cancelOngoingAction(mob);

        // 清零移动输入（xxa/zza 是 moveControl 上次设置的残留值，
        // 不清零的话 travel 会持续按旧输入驱动生物漂移），然后跳过整个 AI tick
        mob.setXxa(0.0F);
        mob.setZza(0.0F);
        mob.setJumping(false);
        ci.cancel();
    }
}
