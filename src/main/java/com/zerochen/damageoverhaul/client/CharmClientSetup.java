package com.zerochen.damageoverhaul.client;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.CharmConfig;
import com.zerochen.damageoverhaul.item.DynamicCharmItem;
import com.zerochen.damageoverhaul.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.Map;

/**
 * 饰品客户端设置：注册 ItemProperty "frame"，用于 model JSON 的 overrides 按 frame 值切换贴图实现动画。
 * 属性函数根据 level.getGameTime() / frameSpeed 计算当前帧，按 frame_order 取对应帧索引返回。
 * 完全无状态，零 NBT 开销。单帧饰品返回 0f（无动画开销）。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CharmClientSetup {

    private static final ResourceLocation FRAME_PROPERTY = new ResourceLocation(DamageOverhaulMod.MOD_ID, "frame");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(CharmClientSetup::registerCharmClientFeatures);
    }

    private static void registerCharmClientFeatures() {
        for (Map.Entry<String, RegistryObject<Item>> entry : ModItems.CHARM_ITEMS.entrySet()) {
            String charmId = entry.getKey();
            Item item = entry.getValue().get();
            CharmConfig cfg = ConfigLoader.getCharmConfig(charmId);
            if (cfg == null) continue;

            // 注册 frame 属性（多帧动画；单帧返回 0f 无开销）
            final List<Integer> frameOrder = cfg.frameOrder;
            final int frameSpeed = cfg.frameSpeed > 0 ? cfg.frameSpeed : 8;
            ItemProperties.register((DynamicCharmItem) item, FRAME_PROPERTY,
                    (stack, level, entity, seed) -> {
                        if (level == null) return 0f;
                        int count = frameOrder.size();
                        if (count <= 1) return 0f;
                        long time = level.getGameTime() / Math.max(1, frameSpeed);
                        int idx = (int) (time % count);
                        return (float) (frameOrder.get(idx));
                    });
        }
        DamageOverhaulMod.LOGGER.info("[CharmClient] 已注册 {} 个饰品的 frame 属性", ModItems.CHARM_ITEMS.size());
    }
}
