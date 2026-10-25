package com.zerochen.damageoverhaul.integration;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备补正的属性点统一桥：more_attributes（org.mantodea.more_attributes）外部源 + 内置属性系统双模。
 *
 * 外部源：该模组为玩家提供 9 个可加点基础能力，已投等级保存在玩家 Capability
 * （IPlayerClassCapability.getAttributes(): name → 已投等级），通过纯反射读取。
 *
 * 内部源：more_attributes 未安装时，使用本模组内置属性系统（capability/InternalAttributes，
 * 自动成长：战斗/承伤/劳作/生活行为涨经验，幂次曲线升级），数据存于玩家 Capability 并同步客户端。
 *
 * 两个源共享同一缩放语义：有效属性点 = 已投等级 − baseLevel（外部可为负，内部恒 ≥ 0）。
 * 外部源存在时内部系统完全休眠（不读不写不结算），卸载后内部数据原样恢复。
 */
public final class MoreAttributesBridge {

    private MoreAttributesBridge() {}

    private static final String MODID = "more_attributes";
    private static final String CAP_HOLDER_CLASS = "org.mantodea.more_attributes.MoreAttributes";
    private static final String CAP_FIELD = "PLAYER_CLASS";
    private static final String CAP_IFACE = "org.mantodea.more_attributes.capability.IPlayerClassCapability";
    private static final String ATTR_LOADER_CLASS = "org.mantodea.more_attributes.datas.AttributeLoader";
    private static final String ATTR_LIST_FIELD = "Attributes";

    /** 未安装/初始化失败时的回退能力清单（该模组内置数据包的 9 个基础能力，与内部属性系统同名） */
    private static final List<String> FALLBACK_ATTRIBUTES = List.of(
            "strength", "health", "focus", "stamina", "endurance",
            "intelligence", "agility", "skill", "luck");
    /** 回退 baseLevel（strength.json 内置 10，其余默认 0；仅在反射失败且内部配置未加载时使用） */
    private static final Map<String, Integer> FALLBACK_BASE_LEVELS = Map.of("strength", 10);

    private static Boolean modPresent = null;
    private static boolean initFailed = false;

    private static Capability<?> playerClassCap;
    private static Method getAttributesMethod;
    private static Method getLevelMethod;

    // 能力元数据（name → baseLevel），外部源来自数据包 AttributeLoader.Attributes，内部源来自 internal_attributes.json
    private static volatile Map<String, Integer> baseLevels = null;

    /** more_attributes 是否已安装（仅指外部模组本身） */
    public static boolean isPresent() {
        if (modPresent == null) {
            modPresent = ModList.get() != null && ModList.get().isLoaded(MODID);
        }
        return modPresent;
    }

    /**
     * 装备补正是否有可用的属性点数据源：
     * 外部 more_attributes 已装载，或内部属性系统已启用（internal_attributes.json: enable_internal_attributes）。
     * 结算/tooltip 侧应判断此方法而非 isPresent()。
     */
    public static boolean isScalingActive() {
        return isPresent() || com.zerochen.damageoverhaul.config.ConfigLoader.isInternalAttributesEnabled();
    }

    private static boolean ensureInit() {
        if (!isPresent()) return false;
        if (initFailed) return false;
        if (playerClassCap != null && getAttributesMethod != null) return true;
        try {
            Class<?> holder = Class.forName(CAP_HOLDER_CLASS);
            Field capField = holder.getField(CAP_FIELD);
            playerClassCap = (Capability<?>) capField.get(null);

            Class<?> iface = Class.forName(CAP_IFACE);
            getAttributesMethod = iface.getMethod("getAttributes");
            getLevelMethod = iface.getMethod("getLevel", String.class);
            return true;
        } catch (Throwable t) {
            initFailed = true;
            return false;
        }
    }

    /** 从玩家身上解析 Capability 实例（失败/不存在返回 null） */
    private static Object resolveCap(Player player) {
        if (!ensureInit() || player == null) return null;
        try {
            var opt = player.getCapability(playerClassCap);
            return opt != null && opt.isPresent() ? opt.resolve().orElse(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取玩家全部能力的【有效属性点】（已投等级 − baseLevel）。
     * 外部源：more_attributes 已安装时走反射（可为负）。
     * 内部源：未安装时走内置属性系统（恒 ≥ 0）。
     */
    public static Map<String, Integer> getEffectivePoints(Player player) {
        if (isPresent()) return getExternalEffectivePoints(player);
        return getInternalEffectivePoints(player);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> getExternalEffectivePoints(Player player) {
        Object cap = resolveCap(player);
        if (cap == null) return Collections.emptyMap();
        try {
            Map<String, Integer> base = (Map<String, Integer>) getAttributesMethod.invoke(cap);
            if (base == null || base.isEmpty()) return Collections.emptyMap();
            Map<String, Integer> levels = refreshBaseLevels();
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : base.entrySet()) {
                int bl = levels.getOrDefault(e.getKey(), 0);
                result.put(e.getKey(), e.getValue() - bl);
            }
            return result;
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    /** 内部属性系统：服务端读 Capability，客户端读同步缓存 */
    private static Map<String, Integer> getInternalEffectivePoints(Player player) {
        Map<String, Integer> levels;
        Map<String, Integer> base = refreshBaseLevels();
        if (player.level().isClientSide()) {
            levels = com.zerochen.damageoverhaul.client.InternalAttributesClient.getStoredLevels();
        } else {
            var capOpt = player.getCapability(com.zerochen.damageoverhaul.capability.InternalAttributesCapability.INTERNAL);
            var cap = capOpt.resolve().orElse(null);
            if (cap == null) return Collections.emptyMap();
            levels = cap.getLevels();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String attr : base.keySet()) {
            int lv = levels.getOrDefault(attr, base.getOrDefault(attr, 0));
            result.put(attr, lv - base.getOrDefault(attr, 0));
        }
        return result;
    }

    /**
     * 获取玩家单个能力的【有效属性点】（已投等级 − baseLevel）。
     */
    public static int getEffectivePoint(Player player, String attributeName) {
        if (attributeName == null) return 0;
        if (isPresent()) {
            Object cap = resolveCap(player);
            if (cap == null) return 0;
            try {
                int level = (int) getLevelMethod.invoke(cap, attributeName);
                return level - refreshBaseLevels().getOrDefault(attributeName, 0);
            } catch (Throwable t) {
                return 0;
            }
        }
        // 内部属性系统
        Map<String, Integer> levels;
        if (player.level().isClientSide()) {
            levels = com.zerochen.damageoverhaul.client.InternalAttributesClient.getStoredLevels();
        } else {
            var capOpt = player.getCapability(com.zerochen.damageoverhaul.capability.InternalAttributesCapability.INTERNAL);
            var cap = capOpt.resolve().orElse(null);
            if (cap == null) return 0;
            levels = cap.getLevels();
        }
        int base = refreshBaseLevels().getOrDefault(attributeName, 0);
        return levels.getOrDefault(attributeName, base) - base;
    }

    /**
     * 读取全部基础能力元数据（name → baseLevel）。
     * 外部源：more_attributes 已安装时反射数据包 AttributeLoader.Attributes。
     * 内部源：未安装时从 internal_attributes.json 的 attributes 配置读取。
     * 结果缓存；reload 时调用 invalidate() 刷新。
     */
    public static Map<String, Integer> refreshBaseLevels() {
        Map<String, Integer> cached = baseLevels;
        if (cached != null) return cached;
        synchronized (MoreAttributesBridge.class) {
            if (baseLevels != null) return baseLevels;
            Map<String, Integer> result = new LinkedHashMap<>();
            boolean ok = false;
            if (isPresent()) {
                try {
                    Class<?> loaderClass = Class.forName(ATTR_LOADER_CLASS);
                    Field listField = loaderClass.getField(ATTR_LIST_FIELD);
                    Object listObj = listField.get(null);
                    if (listObj instanceof List<?> list) {
                        for (Object data : list) {
                            if (data == null) continue;
                            Field nameF = data.getClass().getField("name");
                            Field baseF = data.getClass().getField("baseLevel");
                            Object nameVal = nameF.get(data);
                            if (nameVal instanceof String name) {
                                result.put(name, (int) baseF.get(data));
                            }
                        }
                        ok = true;
                    }
                } catch (Throwable ignored) {
                    // 数据包尚未加载（监听者晚于配置加载）等情况：回退
                }
            }
            if (!ok || result.isEmpty()) {
                // 内部属性系统：从 internal_attributes.json 读取 base_level 配置
                Map<String, Integer> internal = com.zerochen.damageoverhaul.config.ConfigLoader.getInternalBaseLevels();
                if (internal != null && !internal.isEmpty()) {
                    result.putAll(internal);
                    ok = true;
                }
            }
            if (!ok || result.isEmpty()) {
                for (String name : FALLBACK_ATTRIBUTES) {
                    result.put(name, FALLBACK_BASE_LEVELS.getOrDefault(name, 0));
                }
            }
            baseLevels = Collections.unmodifiableMap(result);
            return baseLevels;
        }
    }

    /** 全部基础能力名（有序） */
    public static List<String> getAttributeNames() {
        return List.copyOf(refreshBaseLevels().keySet());
    }

    /** 强制刷新能力元数据缓存（/damageoverhaul reload 时调用） */
    public static void invalidate() {
        baseLevels = null;
    }
}
