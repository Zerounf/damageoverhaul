package com.zerochen.damageoverhaul.client;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Better Combat（BC）兼容层：眩晕时中断 BC 正在进行的挥砍。
 *
 * 工作原理：
 *   BC 的挥砍状态机（upswing）由它自己的 Mixin 注入 Minecraft 类实现，
 *   运行时其状态字段 upswingTicks 与取消方法 cancelUpswing() 会被合并进
 *   net.minecraft.client.Minecraft。本模组不依赖 BC，故只能通过反射访问：
 *     - 未安装 BC：解析不到字段/方法，静默跳过，不影响任何功能；
 *     - 已安装 BC：取消当前挥砍并清零计时器，挥砍中断且不会再结算出攻击。
 *
 * 为什么必须清零 upswingTicks：
 *   BC 的 cancelUpswing() 只停止挥砍动画、并不会归零计时器；若不清零，
 *   BC 的计时器仍会走完并在结束时触发攻击，因此必须同时把字段归零。
 *
 * 动画中断分两步（只调 cancelUpswing 会看到挥砍"继续播完"）：
 *   BC 的 cancelUpswing() 内部以 downWind = 冷却时长 × (1 - 0.5 × upswing_multiplier)
 *   调用 stopAttackAnimation —— 这是约冷却一半时长的"渐出过渡"，属于 BC 设计的
 *   优雅取消（同其佯攻/feint 行为），视觉上挥砍仍在播放。因此这里在其之后
 *   再对本地玩家以 1 tick 渐出调用 stopAttackAnimation(1f)，实现瞬时定格；
 *   其他玩家看到的仍是 cancelUpswing 广播的平滑停止包（观感更自然）。
 *
 * 另注：BC 的「按下攻击」注入在 Minecraft.startAttack() 的 HEAD，而 Forge 的
 * InputEvent.InteractionKeyMappingTriggered 在方法中途才触发，事件取消对 BC 无效，
 * 这也是这里需要单独处理的原因。
 *
 * 诊断：解析完成与首次调用失败都会写日志，便于区分「BC 未安装/版本不匹配」
 * 与「BC 内部实现变更」两类问题。
 */
public final class BetterCombatCompat {

    /** Mixin 合并后成员名保持原样；用后缀匹配以兼容潜在前缀。 */
    private static final String UPSWING_TICKS_SUFFIX = "upswingTicks";
    private static final String CANCEL_UPSWING_SUFFIX = "cancelUpswing";
    private static final String STOP_ATTACK_ANIMATION_SUFFIX = "stopAttackAnimation";

    private static boolean resolved = false;
    private static Field upswingTicksField;
    private static Method cancelUpswingMethod;
    private static Method stopAttackAnimationMethod;
    private static boolean failureLogged = false;

    private BetterCombatCompat() {
    }

    private static void resolve() {
        if (resolved) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return; // 实例尚未创建，下次再试

            Class<?> cls = mc.getClass();

            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == int.class && f.getName().endsWith(UPSWING_TICKS_SUFFIX)) {
                        f.setAccessible(true);
                        upswingTicksField = f;
                        break;
                    }
                }
                if (upswingTicksField != null) break;
            }

            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().endsWith(CANCEL_UPSWING_SUFFIX)) {
                    cancelUpswingMethod = m;
                    break;
                }
            }

            // BC 通过 Mixin 在 AbstractClientPlayer 上追加 stopAttackAnimation(float)，
            // 用于本地瞬时定格挥砍动画（见类注释）。
            for (Method m : AbstractClientPlayer.class.getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class
                        && m.getName().endsWith(STOP_ATTACK_ANIMATION_SUFFIX)) {
                    stopAttackAnimationMethod = m;
                    break;
                }
            }

            resolved = true;
            DamageOverhaulMod.LOGGER.info(
                    "[BC兼容] Better Combat 反射解析完成：upswingTicks={}, cancelUpswing={}, stopAttackAnimation={}",
                    upswingTicksField != null, cancelUpswingMethod != null, stopAttackAnimationMethod != null);
        } catch (Throwable t) {
            // 解析失败也标记为已完成，避免每 tick 反复尝试
            upswingTicksField = null;
            cancelUpswingMethod = null;
            stopAttackAnimationMethod = null;
            resolved = true;
            DamageOverhaulMod.LOGGER.warn("[BC兼容] Better Combat 反射解析失败，兼容逻辑停用", t);
        }
    }

    /**
     * 眩晕期间每 tick 调用：中断 BC 正在进行的挥砍。
     * 未安装 Better Combat 时为空操作。
     */
    public static void interruptSwing() {
        resolve();
        if (!resolved) return;
        if (upswingTicksField == null && cancelUpswingMethod == null && stopAttackAnimationMethod == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            // 1) BC 自身取消路径：停止动画（长渐出）、广播停止包、清 itemUse/挖掘冷却
            if (cancelUpswingMethod != null) {
                cancelUpswingMethod.invoke(mc);
            }
            // 2) 计时器归零：阻止读条结束时结算出攻击
            if (upswingTicksField != null) {
                upswingTicksField.setInt(mc, 0);
            }
            // 3) 本地瞬时定格：以 1 tick 渐出覆盖第 1 步的长渐出，视觉上立即中断
            if (stopAttackAnimationMethod != null) {
                stopAttackAnimationMethod.invoke(mc.player, 1.0f);
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                DamageOverhaulMod.LOGGER.warn("[BC兼容] 中断 Better Combat 挥砍失败（仅记录一次）", t);
            }
        }
    }
}
