package com.zerochen.damageoverhaul.registry;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.CharmConfig;
import com.zerochen.damageoverhaul.item.DynamicCharmItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 饰品物品注册：启动时按 charms.json 注册每个饰品为独立 Item。
 * 与 ModEnchantments 同模式：必须依赖 charms.json 在物品注册窗口前已读取，
 * 因此使用 ConfigLoader.getInitialCharms() 早期预读（不存在则生成默认配置）。
 * 新增饰品条目需重启游戏才能注册对应物品。
 */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, DamageOverhaulMod.MOD_ID);

    // 启动时按 charms.json 注册的饰品物品（id → RegistryObject）
    public static final Map<String, RegistryObject<Item>> CHARM_ITEMS = new LinkedHashMap<>();

    public static void registerAll() {
        List<CharmConfig> charms = ConfigLoader.getInitialCharms();
        for (CharmConfig charm : charms) {
            // 物品 ID：damageoverhaul:<charm_id>（如 fire_charm → damageoverhaul:fire_charm）
            CHARM_ITEMS.put(charm.id, ITEMS.register(charm.id,
                    () -> new DynamicCharmItem(new Item.Properties().stacksTo(1), charm.id)));
            DamageOverhaulMod.LOGGER.info("[ModItems] 注册饰品: {} (伤害类型={}, 帧数={}, 速度={})",
                    charm.id, charm.damageType, charm.textureFrames.size(), charm.frameSpeed);
        }
    }

    public static void registerBus(IEventBus bus) {
        ITEMS.register(bus);
    }
}
