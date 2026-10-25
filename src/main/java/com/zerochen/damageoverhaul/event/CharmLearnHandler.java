package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.item.DynamicCharmItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三方饰品运行时学习（启动全量扫描的兜底）。
 *
 * 启动时 ConfigLoader.scanRegistryForCuriosItems 已按 curios:* 标签全量收录注册表饰品；
 * 但个别模组在运行时才通过内存数据包动态生成标签，或玩家通过特殊方式获得了不带标签却可装备的物品，
 * 启动扫描可能漏收。本处理器在玩家登录与周期性 tick 中遍历其 Curios 槽位，
 * 发现未收录的第三方饰品即批量学习（全0行写入 external_charms.json）。
 *
 * 频率：每 {@link #SCAN_INTERVAL_TICKS} tick 扫描一次在线玩家（默认 200t = 10 秒），
 *       仅遍历少量 Curios 槽位，开销可忽略；新物品通常一次批量 backup+保存。
 * 仅服务端执行；自家 DynamicCharmItem 与 damageoverhaul 命名空间物品跳过（由 charms.json 管理）。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CharmLearnHandler {

    /** 扫描间隔（tick），200t ≈ 10 秒 */
    private static final int SCAN_INTERVAL_TICKS = 200;

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();  // PlayerEvent#getEntity() 已返回 Player
        if (!player.level().isClientSide) {
            scanEquippedCharms(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide) return;
        if (event.player.tickCount % SCAN_INTERVAL_TICKS != 0) return;
        scanEquippedCharms(event.player);
    }

    /** 遍历玩家当前 Curios 槽位中装备的物品，把未收录的第三方物品批量交给 ConfigLoader 学习 */
    private static void scanEquippedCharms(Player player) {
        if (!ConfigLoader.isExternalCharmsEnabled()) return;
        try {
            List<String> unknown = new ArrayList<>();
            CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
                List<SlotResult> slots = handler.findCurios(stack -> !stack.isEmpty());
                for (SlotResult r : slots) {
                    ItemStack stack = r.stack();
                    if (stack.getItem() instanceof DynamicCharmItem) continue;  // 自家饰品不进第三方表
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id == null || "damageoverhaul".equals(id.getNamespace())) continue;
                    String idStr = id.toString();
                    if (ConfigLoader.getExternalCharmConfig(idStr) == null && !unknown.contains(idStr)) {
                        unknown.add(idStr);
                    }
                }
            });
            if (!unknown.isEmpty()) {
                ConfigLoader.learnExternalCharms(unknown);
            }
        } catch (Throwable t) {
            DamageOverhaulMod.LOGGER.warn("[CharmLearn] 遍历玩家 {} 的 Curios 槽位失败: {}",
                    player.getName().getString(), t.getMessage());
        }
    }
}
