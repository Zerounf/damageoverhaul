package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.capability.InternalAttributes;
import com.zerochen.damageoverhaul.capability.InternalAttributesCapability;
import com.zerochen.damageoverhaul.capability.InternalAttributesProvider;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.integration.MoreAttributesBridge;
import com.zerochen.damageoverhaul.registry.ModMessages;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.util.LazyOptional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 内部属性系统的成长事件监听（仅当 more_attributes 未安装且 internal_attributes.json 启用时生效）。
 *
 * 行为 → 经验 → 升级：
 *   战斗：击杀（kill，按目标最大生命放大）、造成伤害（deal_damage，按每点伤害）
 *   承伤：受到伤害（take_damage，按每点承伤）
 *   劳作/移动：挖掘（mine_block）、疾跑（sprint_second，每秒结算）、跳跃（jump）
 *   生活：合成（craft_item）、钓鱼（fish）、交易（trade）、繁殖（breed_animal）、进食（eat_food）
 *
 * 每次获得经验后检查升级（幂次曲线，受 max_level 限制），
 * 升级时给玩家发聊天提示并同步客户端缓存（tooltip 用）。
 */
public class InternalAttributesEvents {

    // ---------------- 生命周期：Capability 挂载 / 死亡克隆 / 登录同步 ----------------

    /** 给所有玩家附加内部属性 Capability */
    @SubscribeEvent
    public static void onAttachCaps(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(InternalAttributesProvider.ID, new InternalAttributesProvider());
        }
    }

    /** 死亡/维度返回重生时克隆数据（internal_attributes.json 属性跨死亡保留） */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        original.reviveCaps();
        LazyOptional<InternalAttributes> oldCap = original.getCapability(InternalAttributesCapability.INTERNAL);
        LazyOptional<InternalAttributes> newCap = event.getEntity().getCapability(InternalAttributesCapability.INTERNAL);
        oldCap.ifPresent(o -> newCap.ifPresent(n -> n.copyFrom(o)));
        original.invalidateCaps();
    }

    /** 登录：向客户端推送内部属性缓存（tooltip 用）。配置同步包由主类另行发送。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ModMessages.sendInternalAttributesToPlayer(sp);
        }
    }

    /** 重生/跨维度：Capability 重建后重新同步 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ModMessages.sendInternalAttributesToPlayer(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ModMessages.sendInternalAttributesToPlayer(sp);
        }
    }

    // ---------------- 行为事件：战斗 ----------------

    /** 击杀：strength + luck（按目标最大生命放大） */
    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        if (!internalActive()) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        LivingEntity victim = event.getEntity();
        double scale = Math.max(1.0, victim.getMaxHealth() / ConfigLoader.getKillHealthDivisor());
        awardScaled(player, "kill", scale);
    }

    /** 造成伤害：strength + focus（按每点伤害） */
    @SubscribeEvent
    public static void onDealDamage(LivingDamageEvent event) {
        if (!internalActive()) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        awardScaled(player, "deal_damage", event.getAmount());
    }

    // ---------------- 行为事件：承伤 ----------------

    /** 受到伤害：endurance + health（按每点承伤） */
    @SubscribeEvent
    public static void onTakeDamage(LivingDamageEvent event) {
        if (!internalActive()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        awardScaled(player, "take_damage", event.getAmount());
    }

    // ---------------- 行为事件：劳作与移动 ----------------

    /** 挖掘方块：stamina */
    @SubscribeEvent
    public static void onMineBlock(BlockEvent.BreakEvent event) {
        if (!internalActive()) return;
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide()) return;
        award(player, "mine_block");
    }

    /** 疾跑计时：每秒结算一次 agility */
    private static final Map<UUID, Integer> SPRINT_TICKS = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!internalActive()) return;
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (player.isSprinting()) {
            int ticks = SPRINT_TICKS.merge(player.getUUID(), 1, Integer::sum);
            if (ticks >= 20) {
                SPRINT_TICKS.put(player.getUUID(), 0);
                award(player, "sprint_second");
            }
        } else {
            SPRINT_TICKS.remove(player.getUUID());
        }
    }

    /** 跳跃：agility */
    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!internalActive()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        award(player, "jump");
    }

    // ---------------- 行为事件：生活 ----------------

    /** 合成物品：skill */
    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!internalActive()) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        award(player, "craft_item");
    }

    /** 钓鱼收获：luck */
    @SubscribeEvent
    public static void onFish(ItemFishedEvent event) {
        if (!internalActive()) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        award(player, "fish");
    }

    /** 村民交易：luck + skill */
    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (!internalActive()) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        award(player, "trade");
    }

    /** 动物繁殖：luck */
    @SubscribeEvent
    public static void onBreed(BabyEntitySpawnEvent event) {
        if (!internalActive()) return;
        Player player = event.getCausedByPlayer();
        if (player == null || player.level().isClientSide()) return;
        award(player, "breed_animal");
    }

    /** 进食：health */
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!internalActive()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        if (!event.getItem().isEdible()) return;
        award(player, "eat_food");
    }

    // ---------------- 经验授予与升级 ----------------

    private static boolean internalActive() {
        return !MoreAttributesBridge.isPresent() && ConfigLoader.isInternalAttributesEnabled();
    }

    /** 按来源配置授予固定经验 */
    private static void award(Player player, String sourceKey) {
        awardScaled(player, sourceKey, 1.0);
    }

    /** 按来源配置授予经验 × 系数（击杀按目标生命放大；伤害/承伤按点数） */
    private static void awardScaled(Player player, String sourceKey, double scale) {
        Map<String, Double> amounts = ConfigLoader.getInternalXpSource(sourceKey);
        if (amounts.isEmpty()) return;
        player.getCapability(InternalAttributesCapability.INTERNAL).ifPresent(cap -> {
            boolean anyLevelUp = false;
            for (Map.Entry<String, Double> e : amounts.entrySet()) {
                if (addXp(player, cap, e.getKey(), e.getValue() * scale)) anyLevelUp = true;
            }
            if (anyLevelUp && player instanceof ServerPlayer sp) {
                ModMessages.sendInternalAttributesToPlayer(sp);
            }
        });
    }

    /**
     * 给能力加经验并结算升级。
     * @return 是否发生了至少一次升级
     */
    private static boolean addXp(Player player, InternalAttributes cap, String attr, double amount) {
        int maxLevel = ConfigLoader.getInternalMaxLevel(attr);
        int level = cap.getLevel(attr);
        if (level >= maxLevel) return false;

        double xp = cap.getXp(attr) + amount;
        boolean leveledUp = false;
        while (level < maxLevel) {
            double required = ConfigLoader.internalXpRequired(attr, level);
            if (xp < required) break;
            xp -= required;
            level++;
            leveledUp = true;
            notifyLevelUp(player, attr, level);
        }
        // 满级后清空本级经验，防止无限累积
        cap.setLevel(attr, level);
        cap.setXp(attr, level >= maxLevel ? 0.0 : xp);
        return leveledUp;
    }

    /** 升级聊天提示：[属性] 力量 Lv.12（能力名走客户端本地化） */
    private static void notifyLevelUp(Player player, String attr, int newLevel) {
        player.displayClientMessage(
                Component.literal("")
                        .append(Component.translatable("message.damageoverhaul.level_up_prefix")
                                .withStyle(net.minecraft.ChatFormatting.GOLD))
                        .append(Component.translatable("attribute.damageoverhaul." + attr)
                                .withStyle(net.minecraft.ChatFormatting.WHITE))
                        .append(Component.literal(" Lv." + newLevel)
                                .withStyle(net.minecraft.ChatFormatting.YELLOW)),
                false);
    }

    /** 玩家退出时清理疾跑计时缓存 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SPRINT_TICKS.remove(event.getEntity().getUUID());
    }

    static {
        // 类加载时确认事件类可用（防御性，附魔台事件在某些 Forge 版本不存在，故未挂钩）
        DamageOverhaulMod.LOGGER.debug("InternalAttributesEvents loaded");
    }
}
