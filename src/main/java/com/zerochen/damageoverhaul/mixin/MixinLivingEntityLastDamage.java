package com.zerochen.damageoverhaul.mixin;

import com.zerochen.damageoverhaul.event.DamageHandler;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 在 LivingEntity.hurt() RETURN 时覆盖 lastDamage：
 * hurt() 在 actuallyHurt() 之后设置 lastDamage=原始伤害(如7.0)，
 * 覆盖 DamageHandler 在 LivingHurtEvent 中设置的值。
 * 后续相同伤害(7.0)会被检查为 7.0<=lastDamage(7.0) → 完全忽略 → 伤害=0。
 * 此 Mixin 在 hurt() 返回时用 0 覆盖 lastDamage，修复此问题。
 *
 * 不使用 @Shadow（refmap 未映射 lastDamage 字段名），改用反射兼容 dev/reobf。
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityLastDamage {

    private static Field lastDamageField = null;
    private static boolean lastDamageFieldInit = false;

    @Inject(method = "hurt", at = @At("RETURN"))
    private void damageoverhaul$fixLastDamage(DamageSource source, float amount,
                                               CallbackInfoReturnable<Boolean> cir) {
        float adjusted = DamageHandler.PENDING_LAST_DAMAGE.get();
        if (adjusted >= 0f) {
            if (!lastDamageFieldInit) {
                lastDamageField = findLastDamageField();
                lastDamageFieldInit = true;
                if (lastDamageField != null) {
                    lastDamageField.setAccessible(true);
                }
            }
            if (lastDamageField != null) {
                try {
                    lastDamageField.setFloat((LivingEntity)(Object)this, adjusted);
                } catch (Exception ignored) {
                }
            }
            DamageHandler.PENDING_LAST_DAMAGE.set(-1f);
        }

        // 负抗性治疗：在 hurt() 伤害实际生效后恢复（returnValue=true 表示伤害未被取消/吞掉）
        float heal = DamageHandler.PENDING_HEALING.get();
        if (heal > 0f) {
            DamageHandler.PENDING_HEALING.set(0f);
            if (Boolean.TRUE.equals(cir.getReturnValue())) {
                ((LivingEntity)(Object)this).heal(heal);
            }
        }
    }

    /**
     * 反射查找 LivingEntity.lastDamage 字段。
     * dev 环境名为 "lastDamage"，reobf 运行时被混淆为 SRG 名。
     */
    private static Field findLastDamageField() {
        // 1) 尝试 dev 名和常见 SRG 名
        for (String n : new String[]{"lastDamage", "f_20896_", "f_20894_", "f_20895_"}) {
            try { return LivingEntity.class.getDeclaredField(n); }
            catch (NoSuchFieldException ignored) {}
        }
        // 2) 兜底：遍历所有 float 字段（排除 invulnerableTime、static）
        for (Field f : LivingEntity.class.getDeclaredFields()) {
            if (f.getType() == float.class
                    && !f.getName().equals("invulnerableTime")
                    && !Modifier.isStatic(f.getModifiers())) {
                return f;
            }
        }
        return null;
    }
}
