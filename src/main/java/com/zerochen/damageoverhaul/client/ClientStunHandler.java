package com.zerochen.damageoverhaul.client;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端眩晕输入拦截器。
 *
 * 当本地玩家处于冲击力眩晕状态时（NBT 标记 damageoverhaul.stun_end_tick 未过期），
 * 完全禁用玩家所有输入：
 *   - 移动（WASD）：在 ClientTickEvent 中将 Input 的 forwardImpulse / leftImpulse 清零
 *   - 跳跃：将 jumping 置 false
 *   - 攻击 / 使用物品 / 交互：取消 InputEvent.InteractionKeyMappingTriggered
 *   - 鼠标点击：取消 InputEvent.MouseButton（非 GUI 状态下）
 *   - 键盘按键：取消 InputEvent.Key（非 GUI 状态下，防止快捷键）
 *   - 外部战斗模组（Better Combat 等）：释放攻击/使用按键并中断正在进行的挥砍
 *
 * 眩晕状态由服务端通过 StunSyncPacket 同步到客户端实体的 persistentData。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, value = Dist.CLIENT)
public class ClientStunHandler {

    /** NBT 标记键名，与 ImpactEffectHandler.STUN_END_TICK_KEY 保持一致 */
    private static final String STUN_END_TICK_KEY = "damageoverhaul.stun_end_tick";

    /**
     * 检查本地玩家是否处于眩晕状态。
     * 读取 persistentData 中的 stun_end_tick，与当前游戏 tick 比较。
     */
    private static boolean isLocalPlayerStunned() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return false;
        long endTick = player.getPersistentData().getLong(STUN_END_TICK_KEY);
        return endTick > 0 && mc.level.getGameTime() < endTick;
    }

    /**
     * 每 tick 清零玩家移动输入（forward/left/jump/sneak），模拟完全无法移动。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.input == null || mc.level == null) return;

        long endTick = mc.player.getPersistentData().getLong(STUN_END_TICK_KEY);
        if (endTick <= 0) return;

        if (mc.level.getGameTime() >= endTick) {
            // 眩晕结束：清除客户端 NBT 标记
            mc.player.getPersistentData().remove(STUN_END_TICK_KEY);
            return;
        }

        // 眩晕中：清零所有移动输入
        mc.player.input.forwardImpulse = 0f;
        mc.player.input.leftImpulse = 0f;
        mc.player.input.jumping = false;
        mc.player.input.shiftKeyDown = false;

        // 释放攻击/使用按键：原版与其它模组都会用 keyAttack.isDown() 判断是否持续攻击，
        // 仅靠事件取消（Post 事件）不会改变按键状态，这里直接把按下状态清掉。
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);

        // 中断外部战斗模组（Better Combat）正在进行的挥砍；未安装时为空操作。
        BetterCombatCompat.interruptSwing();
    }

    /**
     * 取消攻击 / 使用物品 / 交互等映射按键触发。
     * 这个事件在 Minecraft 处理 keyMapping 时触发，取消后不会执行对应的游戏动作。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (isLocalPlayerStunned()) {
            event.setCanceled(true);
        }
    }

    /**
     * 取消鼠标点击（左键攻击 / 右键使用）。
     * 仅在无 GUI 打开时取消，避免影响菜单操作。
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 有 GUI 时不拦截（允许操作菜单）
        if (isLocalPlayerStunned()) {
            event.setCanceled(true);
        }
    }

    /**
     * 取消键盘按键输入（移动键、快捷键等）。
     * 仅在无 GUI 打开时取消，避免影响聊天/菜单输入。
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 有 GUI 时不拦截（允许聊天/菜单）
        if (isLocalPlayerStunned()) {
            event.setCanceled(true);
        }
    }
}
