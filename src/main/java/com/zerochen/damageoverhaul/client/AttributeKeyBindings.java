package com.zerochen.damageoverhaul.client;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 内部属性面板按键：默认 H（玩家可在「选项 → 控制」改键）。
 * MOD 总线注册 KeyMapping，FORGE 总线监听按键打开 AttributesScreen。
 */
public class AttributeKeyBindings {

    public static final String CATEGORY = "key.categories.damageoverhaul";

    public static final KeyMapping OPEN_ATTRIBUTES = new KeyMapping(
            "key.damageoverhaul.open_attributes",
            GLFW.GLFW_KEY_H,
            CATEGORY);

    /** MOD 总线：注册按键映射 */
    @Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_ATTRIBUTES);
        }
    }

    /** FORGE 总线：游戏内按下面板键时打开（有界面时交给 Esc 等常规流程） */
    @Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeBusEvents {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            while (OPEN_ATTRIBUTES.consumeClick()) {
                mc.setScreen(new AttributesScreen());
            }
        }
    }
}
