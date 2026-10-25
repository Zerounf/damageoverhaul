package com.zerochen.damageoverhaul.registry;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.ArmorResistance;
import com.zerochen.damageoverhaul.config.ConfigLoader.ArmorToughness;
import com.zerochen.damageoverhaul.config.ConfigLoader.ImpactEffect;
import com.zerochen.damageoverhaul.config.ConfigLoader.ManaRepairEntry;
import com.zerochen.damageoverhaul.config.ConfigLoader.ProjectileConfig;
import com.zerochen.damageoverhaul.config.ConfigLoader.ProjectileItemMapping;
import com.zerochen.damageoverhaul.config.ConfigLoader.WeaponDistribution;
import com.zerochen.damageoverhaul.config.ConfigLoader.WeaponGuardBreak;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

/**
 * 网络通道：服务端 → 客户端同步配置缓存。
 * 专用服务器中客户端本地 config/ 与服务器可能不一致，
 * 玩家登录时服务器推送武器分布/护甲抗性/射弹配置/mana修复/damageTypes 等数据，
 * 确保客户端 tooltip 显示与服务器实际伤害结算一致。
 */
public class ModMessages {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(DamageOverhaulMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // 配置同步包（服务端→客户端）
        CHANNEL.registerMessage(packetId++,
                ConfigSyncPacket.class,
                ConfigSyncPacket::encode,
                ConfigSyncPacket::decode,
                ConfigSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 内部属性同步包（服务端→客户端：玩家登录/重生/跨维度/升级时推送）
        CHANNEL.registerMessage(packetId++,
                InternalAttributeSyncPacket.class,
                InternalAttributeSyncPacket::encode,
                InternalAttributeSyncPacket::decode,
                InternalAttributeSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 眩晕同步包（服务端→客户端：冲击力效果触发时通知客户端，用于拦截玩家输入）
        CHANNEL.registerMessage(packetId++,
                StunSyncPacket.class,
                StunSyncPacket::encode,
                StunSyncPacket::decode,
                StunSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    /**
     * 向指定玩家发送内部属性同步包。
     * 无论启用/禁用/休眠（more_attributes 接管）都发送状态包，供客户端属性面板显示；
     * 仅启用时携带玩家实际等级与经验。
     */
    public static void sendInternalAttributesToPlayer(ServerPlayer player) {
        int state;
        java.util.Map<String, Integer> levelMap = java.util.Collections.emptyMap();
        java.util.Map<String, Double> xpMap = java.util.Collections.emptyMap();

        if (com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isPresent()) {
            state = com.zerochen.damageoverhaul.client.InternalAttributesClient.STATE_SLEEPING;
        } else if (!ConfigLoader.isInternalAttributesEnabled()) {
            state = com.zerochen.damageoverhaul.client.InternalAttributesClient.STATE_DISABLED;
        } else {
            state = com.zerochen.damageoverhaul.client.InternalAttributesClient.STATE_ACTIVE;
            com.zerochen.damageoverhaul.capability.InternalAttributes cap =
                    player.getCapability(com.zerochen.damageoverhaul.capability.InternalAttributesCapability.INTERNAL)
                            .resolve().orElse(null);
            if (cap != null) {
                levelMap = cap.getLevels();
                xpMap = cap.getXpMap();
            }
        }

        // 能力元数据（base/max，有序）
        java.util.Map<String, int[]> meta = new java.util.LinkedHashMap<>();
        for (String name : ConfigLoader.getInternalAttributeNames()) {
            meta.put(name, new int[]{
                    ConfigLoader.getInternalBaseLevel(name),
                    ConfigLoader.getInternalMaxLevel(name)});
        }

        CHANNEL.sendTo(
                new InternalAttributeSyncPacket(state,
                        ConfigLoader.getInternalLevelBaseXp(),
                        ConfigLoader.getInternalLevelExponent(),
                        meta, levelMap, xpMap),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    /** 向指定玩家发送配置同步包 */
    public static void sendConfigToPlayer(ServerPlayer player) {
        CHANNEL.sendTo(new ConfigSyncPacket(
                        ConfigLoader.getDamageTypes(),
                        ConfigLoader.getWeaponCacheSnapshot(),
                        ConfigLoader.getArmorCacheSnapshot(),
                        ConfigLoader.getProjectileCacheSnapshot(),
                        ConfigLoader.getManaRepairCacheSnapshot(),
                        ConfigLoader.getRangedDamageSource(),
                        ConfigLoader.getDisplayConfigSnapshot(),
                        ConfigLoader.getProjItemMapSnapshot(),
                        ConfigLoader.isAllowDangerousReplace(),
                        ConfigLoader.isExternalCharmsEnabled(),
                        ConfigLoader.getExternalCharmCacheSnapshot(),
                        ConfigLoader.isMobEffectsEnabled(),
                        ConfigLoader.getMobEffectCacheSnapshot(),
                        ConfigLoader.isWeaponScalingEnabled(),
                        ConfigLoader.getWeaponScalingSnapshot(),
                        ConfigLoader.isArmorScalingEnabled(),
                        ConfigLoader.getArmorScalingSnapshot(),
                        ConfigLoader.getScalingAttributes(),
                        ConfigLoader.getWeaponGuardBreakSnapshot(),
                        ConfigLoader.getArmorToughnessSnapshot(),
                        ConfigLoader.getImpactEffectsSnapshot()
                ),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    // =========================== 辅助：FriendlyByteBuf Map 序列化 ===========================

    private static void writeResourceLocationMap(FriendlyByteBuf buf, Map<ResourceLocation, Map<String, Float>> map) {
        buf.writeInt(map.size());
        for (Map.Entry<ResourceLocation, Map<String, Float>> e : map.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            writeFloatMap(buf, e.getValue());
        }
    }

    private static Map<ResourceLocation, Map<String, Float>> readResourceLocationMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<ResourceLocation, Map<String, Float>> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            ResourceLocation k = buf.readResourceLocation();
            Map<String, Float> v = readFloatMap(buf);
            result.put(k, v);
        }
        return result;
    }

    private static void writeFloatMap(FriendlyByteBuf buf, Map<String, Float> map) {
        if (map == null) { buf.writeInt(0); return; }
        buf.writeInt(map.size());
        for (Map.Entry<String, Float> e : map.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeFloat(e.getValue());
        }
    }

    private static Map<String, Float> readFloatMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, Float> r = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf();
            float v = buf.readFloat();
            r.put(k, v);
        }
        return r;
    }

    /** 装备补正表序列化：itemId → ScalingRow（伤害类型 → 能力 → 倍率，两层嵌套稀疏 map） */
    private static void writeScalingMap(FriendlyByteBuf buf, Map<String, ConfigLoader.ScalingRow> map) {
        if (map == null) { buf.writeInt(0); return; }
        buf.writeInt(map.size());
        for (Map.Entry<String, ConfigLoader.ScalingRow> e : map.entrySet()) {
            buf.writeUtf(e.getKey());
            Map<String, Map<String, Float>> multipliers = e.getValue().multipliers;
            buf.writeInt(multipliers.size());
            for (Map.Entry<String, Map<String, Float>> te : multipliers.entrySet()) {
                buf.writeUtf(te.getKey());
                writeFloatMap(buf, te.getValue());
            }
        }
    }

    private static Map<String, ConfigLoader.ScalingRow> readScalingMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, ConfigLoader.ScalingRow> r = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String itemId = buf.readUtf();
            int tn = buf.readInt();
            Map<String, Map<String, Float>> multipliers = new LinkedHashMap<>();
            for (int j = 0; j < tn; j++) {
                String type = buf.readUtf();
                multipliers.put(type, readFloatMap(buf));
            }
            r.put(itemId, new ConfigLoader.ScalingRow(itemId, multipliers));
        }
        return r;
    }

    // ---- 冲击力/韧性系统序列化 ----

    /** 武器削韧表序列化：itemId → {guardBreak, impactEffect} */
    private static void writeWeaponGuardBreakMap(FriendlyByteBuf buf, Map<String, WeaponGuardBreak> map) {
        if (map == null) { buf.writeInt(0); return; }
        buf.writeInt(map.size());
        for (WeaponGuardBreak wgb : map.values()) {
            buf.writeUtf(wgb.itemId);
            buf.writeFloat(wgb.guardBreak);
            buf.writeUtf(wgb.impactEffect == null ? "" : wgb.impactEffect);
        }
    }

    private static Map<String, WeaponGuardBreak> readWeaponGuardBreakMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, WeaponGuardBreak> r = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String itemId = buf.readUtf();
            float guardBreak = buf.readFloat();
            String effect = buf.readUtf();
            r.put(itemId, new WeaponGuardBreak(itemId, guardBreak, effect.isEmpty() ? null : effect));
        }
        return r;
    }

    /** 护甲韧性表序列化：itemId → {toughness} */
    private static void writeArmorToughnessMap(FriendlyByteBuf buf, Map<String, ArmorToughness> map) {
        if (map == null) { buf.writeInt(0); return; }
        buf.writeInt(map.size());
        for (ArmorToughness at : map.values()) {
            buf.writeUtf(at.itemId);
            buf.writeFloat(at.toughness);
        }
    }

    private static Map<String, ArmorToughness> readArmorToughnessMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, ArmorToughness> r = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String itemId = buf.readUtf();
            float toughness = buf.readFloat();
            r.put(itemId, new ArmorToughness(itemId, toughness));
        }
        return r;
    }

    /** 冲击效果预设表序列化：effectId → {upDown, leftRight, forwardBackward, distance, durationTick, aiPauseTick} */
    private static void writeImpactEffectMap(FriendlyByteBuf buf, Map<String, ImpactEffect> map) {
        if (map == null) { buf.writeInt(0); return; }
        buf.writeInt(map.size());
        for (ImpactEffect ie : map.values()) {
            buf.writeUtf(ie.id);
            buf.writeFloat(ie.upDown);
            buf.writeFloat(ie.leftRight);
            buf.writeFloat(ie.forwardBackward);
            buf.writeFloat(ie.distance);
            buf.writeInt(ie.durationTick);
            buf.writeInt(ie.aiPauseTick);
        }
    }

    private static Map<String, ImpactEffect> readImpactEffectMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, ImpactEffect> r = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf();
            float upDown = buf.readFloat();
            float leftRight = buf.readFloat();
            float forwardBackward = buf.readFloat();
            float distance = buf.readFloat();
            int durationTick = buf.readInt();
            int aiPauseTick = buf.readInt();
            r.put(id, new ImpactEffect(id, upDown, leftRight, forwardBackward, distance, durationTick, aiPauseTick));
        }
        return r;
    }

    // =========================== 配置同步包 ===========================

    public static class ConfigSyncPacket {
        final List<String> damageTypes;
        final Map<ResourceLocation, WeaponDistribution> weapons;
        final Map<ResourceLocation, ArmorResistance> armors;
        final Map<ResourceLocation, ProjectileConfig> projectiles;
        final Map<ResourceLocation, ManaRepairEntry> manaRepairs;
        final String rangedDamageSource;
        final Map<String, Boolean> displayConfig;
        final Map<ResourceLocation, ProjectileItemMapping> projItemMap;
        final boolean allowDangerousReplace;
        final boolean externalCharmsEnabled;
        final Map<String, ConfigLoader.ExternalCharmConfig> externalCharms;
        final boolean mobEffectsEnabled;
        final Map<String, ConfigLoader.MobEffectValues> mobEffects;
        final boolean weaponScalingEnabled;
        final Map<String, ConfigLoader.ScalingRow> weaponScaling;
        final boolean armorScalingEnabled;
        final Map<String, ConfigLoader.ScalingRow> armorScaling;
        final List<String> scalingAttributes;
        // 冲击力/韧性系统
        final Map<String, WeaponGuardBreak> weaponGuardBreak;
        final Map<String, ArmorToughness> armorToughness;
        final Map<String, ImpactEffect> impactEffects;

        public ConfigSyncPacket(List<String> damageTypes,
                                Map<ResourceLocation, WeaponDistribution> weapons,
                                Map<ResourceLocation, ArmorResistance> armors,
                                Map<ResourceLocation, ProjectileConfig> projectiles,
                                Map<ResourceLocation, ManaRepairEntry> manaRepairs,
                                String rangedDamageSource,
                                Map<String, Boolean> displayConfig,
                                Map<ResourceLocation, ProjectileItemMapping> projItemMap,
                                boolean allowDangerousReplace,
                                boolean externalCharmsEnabled,
                                Map<String, ConfigLoader.ExternalCharmConfig> externalCharms,
                                boolean mobEffectsEnabled,
                                Map<String, ConfigLoader.MobEffectValues> mobEffects,
                                boolean weaponScalingEnabled,
                                Map<String, ConfigLoader.ScalingRow> weaponScaling,
                                boolean armorScalingEnabled,
                                Map<String, ConfigLoader.ScalingRow> armorScaling,
                                List<String> scalingAttributes,
                                Map<String, WeaponGuardBreak> weaponGuardBreak,
                                Map<String, ArmorToughness> armorToughness,
                                Map<String, ImpactEffect> impactEffects) {
            this.damageTypes = damageTypes;
            this.weapons = weapons;
            this.armors = armors;
            this.projectiles = projectiles;
            this.manaRepairs = manaRepairs;
            this.rangedDamageSource = rangedDamageSource;
            this.displayConfig = displayConfig;
            this.projItemMap = projItemMap;
            this.allowDangerousReplace = allowDangerousReplace;
            this.externalCharmsEnabled = externalCharmsEnabled;
            this.externalCharms = externalCharms;
            this.mobEffectsEnabled = mobEffectsEnabled;
            this.mobEffects = mobEffects;
            this.weaponScalingEnabled = weaponScalingEnabled;
            this.weaponScaling = weaponScaling;
            this.armorScalingEnabled = armorScalingEnabled;
            this.armorScaling = armorScaling;
            this.scalingAttributes = scalingAttributes;
            this.weaponGuardBreak = weaponGuardBreak;
            this.armorToughness = armorToughness;
            this.impactEffects = impactEffects;
        }

        public static void encode(ConfigSyncPacket pkt, FriendlyByteBuf buf) {
            // damageTypes
            buf.writeInt(pkt.damageTypes.size());
            for (String s : pkt.damageTypes) buf.writeUtf(s);

            // weapons
            buf.writeInt(pkt.weapons.size());
            for (Map.Entry<ResourceLocation, WeaponDistribution> e : pkt.weapons.entrySet()) {
                buf.writeResourceLocation(e.getKey());
                buf.writeUtf(e.getValue().displayName == null ? "" : e.getValue().displayName);
                writeFloatMap(buf, e.getValue().damageDistribution);
            }

            // armors
            buf.writeInt(pkt.armors.size());
            for (Map.Entry<ResourceLocation, ArmorResistance> e : pkt.armors.entrySet()) {
                buf.writeResourceLocation(e.getKey());
                buf.writeUtf(e.getValue().displayName == null ? "" : e.getValue().displayName);
                writeFloatMap(buf, e.getValue().resistances);
            }

            // projectiles
            buf.writeInt(pkt.projectiles.size());
            for (Map.Entry<ResourceLocation, ProjectileConfig> e : pkt.projectiles.entrySet()) {
                buf.writeResourceLocation(e.getKey());
                buf.writeBoolean(e.getValue().followDefault);
                writeFloatMap(buf, e.getValue().damageDistribution);
            }

            // mana repairs
            buf.writeInt(pkt.manaRepairs.size());
            for (Map.Entry<ResourceLocation, ManaRepairEntry> e : pkt.manaRepairs.entrySet()) {
                buf.writeResourceLocation(e.getKey());
                buf.writeBoolean(e.getValue().enabled);
                buf.writeInt(e.getValue().manaPerDurability);
            }

            // rangedDamageSource
            buf.writeUtf(pkt.rangedDamageSource);

            // display config
            buf.writeInt(pkt.displayConfig.size());
            for (Map.Entry<String, Boolean> e : pkt.displayConfig.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeBoolean(e.getValue());
            }

            // projItemMap（含 replace_entity 标记）
            buf.writeInt(pkt.projItemMap.size());
            for (Map.Entry<ResourceLocation, ProjectileItemMapping> e : pkt.projItemMap.entrySet()) {
                buf.writeResourceLocation(e.getKey());             // 物品ID
                buf.writeResourceLocation(e.getValue().entityId);  // 映射实体ID
                buf.writeBoolean(e.getValue().replaceEntity);       // 是否替换实体
            }

            // allowDangerousReplace
            buf.writeBoolean(pkt.allowDangerousReplace);

            // externalCharms（第三方饰品加成：物品ID精确匹配）
            buf.writeBoolean(pkt.externalCharmsEnabled);
            buf.writeInt(pkt.externalCharms.size());
            for (Map.Entry<String, ConfigLoader.ExternalCharmConfig> e : pkt.externalCharms.entrySet()) {
                buf.writeUtf(e.getKey());                       // 物品ID
                writeFloatMap(buf, e.getValue().attackBonuses);
                writeFloatMap(buf, e.getValue().defenseResistances);
                buf.writeFloat(e.getValue().toughness);
            }

            // mobEffects（药水效果数值表：类型 → 4个每级数值）
            buf.writeBoolean(pkt.mobEffectsEnabled);
            buf.writeInt(pkt.mobEffects.size());
            for (Map.Entry<String, ConfigLoader.MobEffectValues> e : pkt.mobEffects.entrySet()) {
                buf.writeUtf(e.getKey());                       // 伤害类型
                buf.writeFloat(e.getValue().resistUpPerLevel);
                buf.writeFloat(e.getValue().resistDownPerLevel);
                buf.writeFloat(e.getValue().damageUpPerLevel);
                buf.writeFloat(e.getValue().damageDownPerLevel);
            }

            // weaponScaling / armorScaling（装备补正稀疏表 + 开关 + 能力列名）
            buf.writeBoolean(pkt.weaponScalingEnabled);
            writeScalingMap(buf, pkt.weaponScaling);
            buf.writeBoolean(pkt.armorScalingEnabled);
            writeScalingMap(buf, pkt.armorScaling);
            buf.writeInt(pkt.scalingAttributes == null ? 0 : pkt.scalingAttributes.size());
            if (pkt.scalingAttributes != null) {
                for (String attr : pkt.scalingAttributes) buf.writeUtf(attr);
            }

            // 冲击力/韧性系统
            writeWeaponGuardBreakMap(buf, pkt.weaponGuardBreak);
            writeArmorToughnessMap(buf, pkt.armorToughness);
            writeImpactEffectMap(buf, pkt.impactEffects);
        }

        public static ConfigSyncPacket decode(FriendlyByteBuf buf) {
            // damageTypes
            int n = buf.readInt();
            List<String> damageTypes = new ArrayList<>();
            for (int i = 0; i < n; i++) damageTypes.add(buf.readUtf());

            // weapons
            n = buf.readInt();
            Map<ResourceLocation, WeaponDistribution> weapons = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ResourceLocation id = buf.readResourceLocation();
                String dn = buf.readUtf();
                Map<String, Float> dist = readFloatMap(buf);
                WeaponDistribution wd = new WeaponDistribution();
                wd.displayName = dn.isEmpty() ? null : dn;
                wd.damageDistribution = dist;
                weapons.put(id, wd);
            }

            // armors
            n = buf.readInt();
            Map<ResourceLocation, ArmorResistance> armors = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ResourceLocation id = buf.readResourceLocation();
                String dn = buf.readUtf();
                Map<String, Float> res = readFloatMap(buf);
                ArmorResistance ar = new ArmorResistance();
                ar.displayName = dn.isEmpty() ? null : dn;
                ar.resistances = res;
                armors.put(id, ar);
            }

            // projectiles
            n = buf.readInt();
            Map<ResourceLocation, ProjectileConfig> projectiles = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ResourceLocation id = buf.readResourceLocation();
                boolean follow = buf.readBoolean();
                Map<String, Float> dist = readFloatMap(buf);
                projectiles.put(id, new ProjectileConfig(follow, dist));
            }

            // mana repairs
            n = buf.readInt();
            Map<ResourceLocation, ManaRepairEntry> manaRepairs = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ResourceLocation id = buf.readResourceLocation();
                boolean enabled = buf.readBoolean();
                int cost = buf.readInt();
                manaRepairs.put(id, new ManaRepairEntry(enabled, cost));
            }

            // rangedDamageSource
            String ranged = buf.readUtf();

            // display config
            n = buf.readInt();
            Map<String, Boolean> dc = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                dc.put(buf.readUtf(), buf.readBoolean());
            }

            // projItemMap
            n = buf.readInt();
            Map<ResourceLocation, ProjectileItemMapping> projItemMap = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ResourceLocation itemId = buf.readResourceLocation();
                ResourceLocation entityId = buf.readResourceLocation();
                boolean replace = buf.readBoolean();
                projItemMap.put(itemId, new ProjectileItemMapping(entityId, replace));
            }

            // allowDangerousReplace
            boolean allowDangerous = buf.readBoolean();

            // externalCharms
            boolean extEnabled = buf.readBoolean();
            int extN = buf.readInt();
            Map<String, ConfigLoader.ExternalCharmConfig> externalCharms = new LinkedHashMap<>();
            for (int i = 0; i < extN; i++) {
                String itemId = buf.readUtf();
                Map<String, Float> atk = readFloatMap(buf);
                Map<String, Float> def = readFloatMap(buf);
                float toughness = buf.readFloat();
                externalCharms.put(itemId, new ConfigLoader.ExternalCharmConfig(itemId, atk, def, toughness));
            }

            // mobEffects
            boolean meEnabled = buf.readBoolean();
            int meN = buf.readInt();
            Map<String, ConfigLoader.MobEffectValues> mobEffects = new LinkedHashMap<>();
            for (int i = 0; i < meN; i++) {
                String type = buf.readUtf();
                mobEffects.put(type, new ConfigLoader.MobEffectValues(
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
            }

            // weaponScaling / armorScaling
            boolean wsEnabled = buf.readBoolean();
            Map<String, ConfigLoader.ScalingRow> weaponScaling = readScalingMap(buf);
            boolean asEnabled = buf.readBoolean();
            Map<String, ConfigLoader.ScalingRow> armorScaling = readScalingMap(buf);
            int saN = buf.readInt();
            List<String> scalingAttributes = new ArrayList<>();
            for (int i = 0; i < saN; i++) scalingAttributes.add(buf.readUtf());

            // 冲击力/韧性系统
            Map<String, WeaponGuardBreak> weaponGuardBreak = readWeaponGuardBreakMap(buf);
            Map<String, ArmorToughness> armorToughness = readArmorToughnessMap(buf);
            Map<String, ImpactEffect> impactEffects = readImpactEffectMap(buf);

            return new ConfigSyncPacket(damageTypes, weapons, armors, projectiles, manaRepairs, ranged, dc,
                    projItemMap, allowDangerous, extEnabled, externalCharms, meEnabled, mobEffects,
                    wsEnabled, weaponScaling, asEnabled, armorScaling, scalingAttributes,
                    weaponGuardBreak, armorToughness, impactEffects);
        }

        public static void handle(ConfigSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                // 在客户端主线程更新 ConfigLoader 缓存
                ConfigLoader.applySyncedConfig(pkt.damageTypes, pkt.weapons, pkt.armors,
                        pkt.projectiles, pkt.manaRepairs, pkt.rangedDamageSource, pkt.displayConfig,
                        pkt.projItemMap, pkt.allowDangerousReplace,
                        pkt.externalCharmsEnabled, pkt.externalCharms,
                        pkt.mobEffectsEnabled, pkt.mobEffects,
                        pkt.weaponScalingEnabled, pkt.weaponScaling,
                        pkt.armorScalingEnabled, pkt.armorScaling, pkt.scalingAttributes,
                        pkt.weaponGuardBreak, pkt.armorToughness, pkt.impactEffects);
                DamageOverhaulMod.LOGGER.info("[ConfigSync] 已接收并应用服务端配置: 武器({}) 护甲({}) 射弹({}) Mana({}) damageTypes({}) 射弹映射({}) 第三方饰品({}) 药水效果({}) 武器补正({}) 盔甲补正({}) 削韧({}) 护甲韧性({}) 冲击效果({}) dangerousReplace={}",
                        pkt.weapons.size(), pkt.armors.size(), pkt.projectiles.size(),
                        pkt.manaRepairs.size(), pkt.damageTypes.size(), pkt.projItemMap.size(),
                        pkt.externalCharms.size(), pkt.mobEffects.size(),
                        pkt.weaponScaling.size(), pkt.armorScaling.size(),
                        pkt.weaponGuardBreak.size(), pkt.armorToughness.size(), pkt.impactEffects.size(),
                        pkt.allowDangerousReplace);
            });
            ctx.setPacketHandled(true);
        }
    }

    // =========================== 内部属性同步包 ===========================

    /**
     * 内部属性同步包（服务端→客户端）。
     * 内容：状态（启用/禁用/休眠）+ 升级曲线 + 能力元数据（base/max，有序）
     *       + 能力名 → 当前等级（含 baseLevel）+ 能力名 → 本级经验进度。
     * 三种状态均发送；禁用/休眠时等级/经验表为空。
     * 客户端接收后存入 InternalAttributesClient 缓存，供属性面板与 tooltip 读取。
     */
    public static class InternalAttributeSyncPacket {
        final int state;
        final double baseXp;
        final double exponent;
        final Map<String, int[]> meta;
        final Map<String, Integer> levels;
        final Map<String, Double> xp;

        public InternalAttributeSyncPacket(int state, double baseXp, double exponent,
                                           Map<String, int[]> meta,
                                           Map<String, Integer> levels, Map<String, Double> xp) {
            this.state = state;
            this.baseXp = baseXp;
            this.exponent = exponent;
            this.meta = meta;
            this.levels = levels;
            this.xp = xp;
        }

        public static void encode(InternalAttributeSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.state);
            buf.writeDouble(pkt.baseXp);
            buf.writeDouble(pkt.exponent);

            Map<String, int[]> metaMap = pkt.meta == null ? Collections.emptyMap() : pkt.meta;
            buf.writeInt(metaMap.size());
            for (Map.Entry<String, int[]> e : metaMap.entrySet()) {
                buf.writeUtf(e.getKey());
                int[] v = e.getValue();
                buf.writeInt(v.length > 0 ? v[0] : 0);
                buf.writeInt(v.length > 1 ? v[1] : 100);
            }

            Map<String, Integer> lv = pkt.levels == null ? Collections.emptyMap() : pkt.levels;
            buf.writeInt(lv.size());
            for (Map.Entry<String, Integer> e : lv.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeInt(e.getValue());
            }
            Map<String, Double> xpMap = pkt.xp == null ? Collections.emptyMap() : pkt.xp;
            buf.writeInt(xpMap.size());
            for (Map.Entry<String, Double> e : xpMap.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeDouble(e.getValue());
            }
        }

        public static InternalAttributeSyncPacket decode(FriendlyByteBuf buf) {
            int state = buf.readByte();
            double baseXp = buf.readDouble();
            double exponent = buf.readDouble();

            int mn = buf.readInt();
            Map<String, int[]> meta = new LinkedHashMap<>();
            for (int i = 0; i < mn; i++) {
                String name = buf.readUtf();
                meta.put(name, new int[]{buf.readInt(), buf.readInt()});
            }

            int ln = buf.readInt();
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (int i = 0; i < ln; i++) levels.put(buf.readUtf(), buf.readInt());
            int xn = buf.readInt();
            Map<String, Double> xp = new LinkedHashMap<>();
            for (int i = 0; i < xn; i++) xp.put(buf.readUtf(), buf.readDouble());
            return new InternalAttributeSyncPacket(state, baseXp, exponent, meta, levels, xp);
        }

        public static void handle(InternalAttributeSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                com.zerochen.damageoverhaul.client.InternalAttributesClient.set(
                        pkt.state, pkt.baseXp, pkt.exponent, pkt.meta, pkt.levels, pkt.xp);
            });
            ctx.setPacketHandled(true);
        }
    }

    /**
     * 眩晕同步包（服务端→客户端）。
     * 内容：目标实体 ID + 眩晕结束 tick 时间。
     * 客户端收到后存入 ClientStunHandler 缓存，用于在 ClientTickEvent / InputEvent 中拦截玩家输入。
     */
    public static class StunSyncPacket {
        final int entityId;
        final long stunEndTick;

        public StunSyncPacket(int entityId, long stunEndTick) {
            this.entityId = entityId;
            this.stunEndTick = stunEndTick;
        }

        public static void encode(StunSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.entityId);
            buf.writeLong(pkt.stunEndTick);
        }

        public static StunSyncPacket decode(FriendlyByteBuf buf) {
            return new StunSyncPacket(buf.readInt(), buf.readLong());
        }

        public static void handle(StunSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return;
                net.minecraft.world.entity.Entity entity = mc.level.getEntity(pkt.entityId);
                if (entity == null) return;
                // 将眩晕结束时间写入实体 persistentData，供客户端输入拦截检查
                entity.getPersistentData().putLong("damageoverhaul.stun_end_tick", pkt.stunEndTick);
            });
            ctx.setPacketHandled(true);
        }
    }

    /** 向指定玩家发送眩晕同步包（仅对玩家目标发送，生物目标用 setNoAi 即可） */
    public static void sendStunToPlayer(ServerPlayer player, int targetEntityId, long stunEndTick) {
        CHANNEL.sendTo(new StunSyncPacket(targetEntityId, stunEndTick),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }
}
