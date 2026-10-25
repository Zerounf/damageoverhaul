package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.ArmorToughness;
import com.zerochen.damageoverhaul.config.ConfigLoader.EntityResistance;
import com.zerochen.damageoverhaul.config.ConfigLoader.ImpactEffect;
import com.zerochen.damageoverhaul.config.ConfigLoader.WeaponGuardBreak;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冲击力/韧性系统（动态韧性版）。
 *
 * <h3>韧性</h3>
 * 每个实体持有动态韧性值 currentToughness，模板值 maxToughness = 基础 + Σ护甲toughness + Σ饰品toughness。
 * 受击时 currentToughness -= 武器guardBreak；当 currentToughness <= 0 时韧性被打破，触发冲击力效果。
 *
 * 回复规则：
 *   - 玩家：每 tick 线性回复 (maxToughness - currentToughness) / 40（约 2 秒回满），无延迟、无需停止受损。
 *   - 非玩家生物：停止受损 1 秒（20 tick）后，以同样线性速度回复。
 *   - 韧性被打破且受到冲击力效果影响后，重置回 maxToughness（免疫/未触发效果时不重置）。
 *   - 换装导致 maxToughness 变化时，currentToughness 按比例缩放：current = current * (newMax / oldMax)。
 *
 * <h3>冲击力</h3>
 * 武器可关联 impactEffect（效果 ID，可为 null）。韧性被打破时触发该效果。
 * 抗冲击（仅玩家基础 + 生物基础）：
 *   - immuneImpactEffects：列出的冲击效果 ID 被完全无视。
 *   - impactEffectRemap：将冲击力 D 重映射为冲击力 C（key=原效果ID, value=替换效果ID）。
 * 削韧值 guardBreak 不受抗冲击影响，直接扣除韧性。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID)
public class ImpactEffectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("DamageOverhaul|Impact");

    /** 非玩家生物停止受损后开始回复的延迟 tick 数（1 秒 = 20 tick） */
    private static final long REGEN_DELAY_TICKS = 20L;
    /** 回复到满所需 tick 数（2 秒 = 40 tick），线性回复速度 = (max - current) / 40 */
    private static final float REGEN_TICKS = 40f;

    /** NBT 标记：眩晕结束时的游戏 tick 时间（MixinMobStunAI 据此停用 Mob AI 但保留物理） */
    public static final String STUN_END_TICK_KEY = "damageoverhaul.stun_end_tick";

    /** 动态韧性状态 */
    private static class ToughnessState {
        float current;
        long lastDamageTick;
        float lastMax;

        ToughnessState(float current, long lastDamageTick, float lastMax) {
            this.current = current;
            this.lastDamageTick = lastDamageTick;
            this.lastMax = lastMax;
        }
    }

    /** 实体 UUID → 韧性状态（服务端权威；客户端不参与韧性计算，仅用于显示） */
    private static final Map<UUID, ToughnessState> TOUGHNESS_STATES = new ConcurrentHashMap<>();

    /**
     * 伤害结算后检查削韧与冲击触发。
     * 由 DamageHandler.applyModifiedDamage 在伤害结算完成后调用。
     */
    public static void checkImpact(LivingEntity target, net.minecraft.world.damagesource.DamageSource source) {
        if (target == null || target.level().isClientSide) return;
        Entity attackerEntity = source.getEntity();
        if (!(attackerEntity instanceof LivingEntity attacker)) return;

        // 获取攻击者武器（主手优先，空则副手，仍空则走空手逻辑）
        ItemStack weaponStack = attacker.getMainHandItem();
        if (weaponStack.isEmpty()) {
            weaponStack = attacker.getOffhandItem();
        }

        float guardBreak;
        String impactEffectId;

        if (!weaponStack.isEmpty()) {
            // 有武器：从 weapon_guard_break.json 读取
            ResourceLocation weaponId = BuiltInRegistries.ITEM.getKey(weaponStack.getItem());
            if (weaponId == null) return;
            WeaponGuardBreak wgb = ConfigLoader.getWeaponGuardBreak(weaponId.toString());
            if (wgb == null || wgb.guardBreak <= 0) return;
            guardBreak = wgb.guardBreak;
            impactEffectId = wgb.impactEffect;
        } else {
            // 空手：从 unarmed_distributions.json 读取生物/玩家的裸击配置
            ResourceLocation attackerId = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType());
            if (attackerId == null) return;
            ConfigLoader.WeaponDistribution unarmed = ConfigLoader.getUnarmedDistribution(attackerId);
            if (unarmed == null || unarmed.guardBreak <= 0) return;
            guardBreak = unarmed.guardBreak;
            impactEffectId = unarmed.impactEffect;
        }

        // 计算目标最大韧性
        float maxToughness = calculateMaxToughness(target);
        if (maxToughness <= 0) return;

        // 获取/创建动态韧性状态，按比例缩放
        UUID uuid = target.getUUID();
        long currentTick = target.level().getGameTime();
        ToughnessState state = TOUGHNESS_STATES.get(uuid);
        if (state == null) {
            // 首次受击，初始化为最大值
            state = new ToughnessState(maxToughness, currentTick, maxToughness);
            TOUGHNESS_STATES.put(uuid, state);
        } else {
            // 换装/属性变化导致 max 变化时，按比例缩放 current
            if (state.lastMax > 0 && Math.abs(state.lastMax - maxToughness) > 1e-4) {
                state.current = state.current * (maxToughness / state.lastMax);
                state.lastMax = maxToughness;
            }
        }

        // 扣除削韧值
        state.current -= guardBreak;
        state.lastDamageTick = currentTick;

        // 韧性未被打破 → 不触发冲击效果
        if (state.current > 0) return;

        // ===== 韧性被打破：处理冲击力效果 =====
        if (impactEffectId == null) {
            // 武器/空手无关联冲击效果 → 只扣韧，不触发效果
            LOGGER.debug("[Impact] {} 被 {} 打破韧性，但无冲击效果",
                    target.getName().getString(), attacker.getName().getString());
            return;
        }

        // 抗冲击：免疫列表检查
        List<String> immune = getImmuneImpactEffects(target);
        if (immune != null && immune.contains(impactEffectId)) {
            LOGGER.debug("[Impact] {} 免疫冲击效果 {}", target.getName().getString(), impactEffectId);
            return;
        }

        // 抗冲击：重映射检查
        Map<String, String> remap = getImpactEffectRemap(target);
        String effectId = impactEffectId;
        if (remap != null && remap.containsKey(effectId)) {
            String remapped = remap.get(effectId);
            LOGGER.debug("[Impact] {} 冲击效果 {} 重映射为 {}", target.getName().getString(), effectId, remapped);
            effectId = remapped;
        }

        // 查找冲击效果预设
        ImpactEffect effect = ConfigLoader.getImpactEffect(effectId);
        if (effect == null) {
            // 效果未配置 → 只扣韧
            LOGGER.debug("[Impact] {} 冲击效果 {} 未配置", target.getName().getString(), effectId);
            return;
        }

        // 施加位移
        applyDirectionalPush(target, attacker, effect);

        // 施加眩晕：用 NBT 标记结束时间 + 完全停用目标 AI
        if (effect.durationTick > 0) {
            applyStun(target, effect.durationTick);
        }

        // 受到冲击力影响后，韧性重置回最大值
        state.current = maxToughness;

        LOGGER.info("[Impact] {} 被 {} 打破韧性（削韧={}），触发效果={} (up={}, lr={}, fb={}, dist={}, stun={}t)，韧性已重置",
                target.getName().getString(), attacker.getName().getString(),
                guardBreak, effect.id, effect.upDown, effect.leftRight, effect.forwardBackward, effect.distance, effect.durationTick);
    }

    /**
     * 对目标施加眩晕：用 NBT 标记眩晕结束时间。
     *   - Mob：AI 停用由 MixinMobStunAI 在 serverAiStep() 中拦截（AI 全停但物理保留，
     *     因此 applyDirectionalPush 的击退速度可以正常结算——setNoAi 会连 travel 一起
     *     冻结，导致击退速度永远不被消费、生物原地不动，故弃用）。
     *   - 玩家：发送网络包通知客户端拦截所有输入（移动/攻击/跳跃/使用物品/交互）
     * 眩晕结束时间到后由 MixinMobStunAI 自动恢复 AI。
     */
    private static void applyStun(LivingEntity target, int durationTick) {
        long endTick = target.level().getGameTime() + durationTick;
        CompoundTag tag = target.getPersistentData();
        tag.putLong(STUN_END_TICK_KEY, endTick);

        if (target instanceof Mob mob) {
            // 即时清理：取消当前正在进行的行为 + 停止寻路
            // （AI 的持续暂停与每 tick 重复取消由 MixinMobStunAI 处理）
            cancelOngoingAction(mob);
        } else if (target instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            // 玩家：中断正在进行的物品使用（拉弓/持弩/喝药等），读条作废且松手不会射出
            // （stopUsingItem 只清标记、不复位 releaseUsing，故不会触发释放效果）
            serverPlayer.stopUsingItem();
            // 发送网络包通知客户端拦截所有输入（保留物理速度，击退可生效）
            com.zerochen.damageoverhaul.registry.ModMessages.sendStunToPlayer(
                    serverPlayer, target.getId(), endTick);
        }
    }

    /**
     * 取消 Mob 当前正在进行的主动行为。眩晕期间由 MixinMobStunAI 每 tick 重复调用，
     * 因为这些行为可能在眩晕期间被重新触发。
     *
     *   - stopUsingItem()：中断拉弓/持弩蓄力/喝药/投掷等物品使用动作（不依赖 AI，需显式中断）
     *   - setTarget(null) + navigation.stop()：清除索敌目标、停止寻路
     *   - Warden：中止正在进行的音爆行为并回归基态（doStop 会触发 SonicBoom.stop() 设置冷却），
     *     但不改动其脑记忆（保留索敌/愤怒状态）
     *
     * 注意：苦力怕自爆由 Creeper.tick() 驱动（不经过 serverAiStep），
     * 无法在此处取消，改由 MixinCreeperStun 处理。
     */
    public static void cancelOngoingAction(Mob mob) {
        // 中断使用中的物品（拉弓/持弩/喝药/使用物品）
        if (mob.isUsingItem()) {
            mob.stopUsingItem();
        }
        // 清除索敌 + 停止寻路
        mob.setTarget(null);
        mob.getNavigation().stop();
        // 监守者：仅中止正在进行的音爆行为并回归基态，不改动脑记忆
        if (mob instanceof Warden warden) {
            ServerLevel level = (ServerLevel) warden.level();
            long gameTime = level.getGameTime();
            for (BehaviorControl<? super Warden> behavior : warden.getBrain().getRunningBehaviors()) {
                if (behavior instanceof SonicBoom) {
                    behavior.doStop(level, warden, gameTime);
                }
            }
        }
    }

    /**
     * 计算目标最大韧性 = base + Σarmor.toughness + Σcharm.toughness
     *   玩家 base = player_config.base_toughness
     *   mob   base = entity_resistances.base_toughness
     */
    private static float calculateMaxToughness(LivingEntity target) {
        float base;
        if (target instanceof Player player) {
            base = ConfigLoader.getPlayerBaseToughness();
            base += CharmEffectHandler.getTotalToughness(player);
        } else {
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            EntityResistance er = (entityId != null) ? ConfigLoader.getResistance(entityId) : null;
            base = (er != null) ? er.baseToughness : 0f;
        }

        // 护甲韧性
        float armorToughness = 0f;
        for (ItemStack slot : target.getArmorSlots()) {
            if (slot.isEmpty()) continue;
            ResourceLocation armorId = BuiltInRegistries.ITEM.getKey(slot.getItem());
            if (armorId == null) continue;
            ArmorToughness at = ConfigLoader.getArmorToughness(armorId.toString());
            if (at != null) armorToughness += at.toughness;
        }

        return base + armorToughness;
    }

    /** 获取目标的免疫冲击效果列表（玩家基础 or 生物基础） */
    private static List<String> getImmuneImpactEffects(LivingEntity target) {
        if (target instanceof Player) {
            return ConfigLoader.getPlayerImmuneImpactEffects();
        }
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        EntityResistance er = (entityId != null) ? ConfigLoader.getResistance(entityId) : null;
        return (er != null) ? er.immuneImpactEffects : null;
    }

    /** 获取目标的冲击效果重映射 Map（玩家基础 or 生物基础） */
    private static Map<String, String> getImpactEffectRemap(LivingEntity target) {
        if (target instanceof Player) {
            return ConfigLoader.getPlayerImpactEffectRemap();
        }
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        EntityResistance er = (entityId != null) ? ConfigLoader.getResistance(entityId) : null;
        return (er != null) ? er.impactEffectRemap : null;
    }

    // ======================== 韧性回复：LivingTickEvent ========================

    /**
     * 每 tick 更新韧性回复：
     *   - 玩家：每 tick 线性回复 (max - current) / 40
     *   - 非玩家：停止受损超过 20 tick 后，以同样速度回复
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide) return;

        // ===== 眩晕标记过期清理 =====
        // Mob 的 AI 停用/恢复由 MixinMobStunAI 在 serverAiStep() 中处理；
        // 玩家的输入拦截由客户端 ClientStunHandler 处理。
        // 注意：眩晕期间不得清零速度，否则击退位移会被抵消。
        CompoundTag tag = entity.getPersistentData();
        if (tag.contains(STUN_END_TICK_KEY)) {
            long stunEndTick = tag.getLong(STUN_END_TICK_KEY);
            if (entity.level().getGameTime() >= stunEndTick) {
                // 眩晕结束：移除标记（Mixin 对 Mob 的 AI 拦截随之自动停止）
                tag.remove(STUN_END_TICK_KEY);
            } else if (!(entity instanceof Mob) && !(entity instanceof Player)) {
                // 非 Mob 非玩家实体：无 Mixin 覆盖，仅冻结水平移动兜底
                Vec3 motion = entity.getDeltaMovement();
                entity.setDeltaMovement(0, motion.y, 0);
            }
        }

        // ===== 韧性回复 =====
        UUID uuid = entity.getUUID();
        ToughnessState state = TOUGHNESS_STATES.get(uuid);
        if (state == null) return;

        // 计算当前 max（可能因换装变化）
        float maxToughness = calculateMaxToughness(entity);
        if (maxToughness <= 0) {
            TOUGHNESS_STATES.remove(uuid);
            return;
        }

        // 按比例缩放（若 max 变化）
        if (state.lastMax > 0 && Math.abs(state.lastMax - maxToughness) > 1e-4) {
            state.current = state.current * (maxToughness / state.lastMax);
            state.lastMax = maxToughness;
        }

        // 已回复满则跳过
        if (state.current >= maxToughness) {
            state.current = maxToughness;
            return;
        }

        boolean shouldRegen;
        if (entity instanceof Player) {
            // 玩家：时刻回复
            shouldRegen = true;
        } else {
            // 非玩家：停止受损超过 20 tick 后回复
            shouldRegen = (entity.level().getGameTime() - state.lastDamageTick) >= REGEN_DELAY_TICKS;
        }

        if (shouldRegen) {
            // 线性回复：每 tick 回复 (max - current) / 40
            state.current += (maxToughness - state.current) / REGEN_TICKS;
            if (state.current > maxToughness) state.current = maxToughness;
        }
    }

    // ======================== 位移与击退 ========================

    /**
     * 按三轴方向预设施加位移（相对受害者朝向）。
     *   up_down: +上升 / -下降
     *   left_right: +右 / -左
     *   forward_backward: +前 / -后
     * 三轴向量叠加后归一化，乘以 distance 得到最终位移。
     */
    private static void applyDirectionalPush(LivingEntity target, LivingEntity attacker, ImpactEffect effect) {
        Vec3 pushDir = computeDirectionVector(target, effect);
        if (pushDir == null) return;
        double length = pushDir.length();
        if (length < 1e-6) return;
        Vec3 normalized = pushDir.scale(1.0 / length);
        Vec3 motion = normalized.scale(effect.distance * 0.3);
        target.push(motion.x, motion.y + 0.2, motion.z);
        target.hurtMarked = true;
    }

    /**
     * 根据三轴数值计算位移向量（相对受害者朝向）。
     * forward = (-sin(yaw), 0, cos(yaw))
     * right   = (cos(yaw), 0, sin(yaw))  = (forwardZ, 0, -forwardX)
     * up      = (0, 1, 0)
     * 最终 = up_down * up + left_right * right + forward_backward * forward
     */
    private static Vec3 computeDirectionVector(LivingEntity target, ImpactEffect effect) {
        float yaw = target.getYRot();
        double rad = Math.toRadians(yaw);
        double forwardX = -Math.sin(rad);
        double forwardZ = Math.cos(rad);
        // right = (forwardZ, 0, -forwardX)
        double rightX = forwardZ;
        double rightZ = -forwardX;

        double x = effect.forwardBackward * forwardX + effect.leftRight * rightX;
        double y = effect.upDown;
        double z = effect.forwardBackward * forwardZ + effect.leftRight * rightZ;
        return new Vec3(x, y, z);
    }
}
