package com.zerochen.damageoverhaul;

import com.mojang.logging.LogUtils;
import com.zerochen.damageoverhaul.client.TooltipHandler;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.event.DamageHandler;
import com.zerochen.damageoverhaul.event.InternalAttributesEvents;
import com.zerochen.damageoverhaul.event.ManaRepairHandler;
import com.zerochen.damageoverhaul.registry.ModEnchantments;
import com.zerochen.damageoverhaul.registry.ModItems;
import com.zerochen.damageoverhaul.registry.ModMessages;
import com.zerochen.damageoverhaul.registry.ModMobEffects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(DamageOverhaulMod.MOD_ID)
public class DamageOverhaulMod {
    public static final String MOD_ID = "damageoverhaul";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DamageOverhaulMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 网络通道注册（必须在mod构造器中，在事件使用前完成）
        ModMessages.register();

        // 附魔注册（必须在mod构造器中，附魔数量由damage_types.json决定）
        ModEnchantments.registerAll();
        ModEnchantments.registerBus(modBus);

        // 药水效果注册（必须在mod构造器中，效果数量由damage_types.json决定：每类型4个）
        ModMobEffects.registerAll();
        ModMobEffects.registerBus(modBus);

        // 饰品物品注册（必须在mod构造器中，数量由charms.json决定）
        ModItems.registerAll();
        ModItems.registerBus(modBus);

        // 内部属性 Capability 注册（more_attributes 未安装时作为装备补正数据源）
        modBus.addListener(com.zerochen.damageoverhaul.capability.InternalAttributesCapability::register);

        MinecraftForge.EVENT_BUS.register(new DamageHandler());
        MinecraftForge.EVENT_BUS.register(new ManaRepairHandler());
        MinecraftForge.EVENT_BUS.register(InternalAttributesEvents.class);
        MinecraftForge.EVENT_BUS.addListener(ConfigLoader::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(ConfigLoader::onRegisterCommands);
        modBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);

        // TooltipHandler 仅在客户端实例化（避免 dedicated server 加载 @OnlyIn(CLIENT) 类触发 NoClassDefFoundError）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new TooltipHandler());
        }
    }

    /** 玩家进入服务器 → 服务端推送所有影响 Tooltip/伤害结算 的配置缓存 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            ModMessages.sendConfigToPlayer(sp);
            LOGGER.info("[ConfigSync] 向玩家 {} 发送配置同步包（服务端缓存：武器/护甲/射弹/mana/damageTypes/display开关）",
                    sp.getName().getString());
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ConfigLoader::loadAllConfigs);
    }
}
