package com.zerochen.damageoverhaul.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端侧内部属性缓存（由服务端 InternalAttributeSyncPacket 推送）。
 * 用于属性面板与 tooltip 显示；纯 Java 结构，不引用客户端 MC 类，
 * 保证专用服务器上被桥接器类路径引用时也不会误加载客户端类。
 *
 * 缓存内容：
 *   state    —— ACTIVE / DISABLED / SLEEPING（more_attributes 接管）
 *   升级曲线 baseXp / exponent（与服务端 internal_attributes.json 一致）
 *   meta     —— 能力名 → {baseLevel, maxLevel}
 *   levels   —— 已显式存储的等级（未存储的能力回落到 baseLevel）
 *   xp       —— 能力名 → 本级经验进度
 */
public final class InternalAttributesClient {

    private InternalAttributesClient() {}

    /** 功能禁用（含尚未收到任何同步包的初始态） */
    public static final int STATE_DISABLED = 0;
    /** 内部属性系统启用 */
    public static final int STATE_ACTIVE = 1;
    /** 外部 more_attributes 模组在场，内部系统休眠 */
    public static final int STATE_SLEEPING = 2;

    private static volatile int state = STATE_DISABLED;
    private static volatile double baseXp = 100.0;
    private static volatile double exponent = 1.5;
    private static volatile Map<String, int[]> meta = Collections.emptyMap();
    private static volatile Map<String, Integer> levels = Collections.emptyMap();
    private static volatile Map<String, Double> xp = Collections.emptyMap();

    public static void set(int newState, double newBaseXp, double newExponent,
                           Map<String, int[]> newMeta,
                           Map<String, Integer> newLevels, Map<String, Double> newXp) {
        state = newState;
        baseXp = Math.max(1.0, newBaseXp);
        exponent = Math.max(0.1, newExponent);
        meta = newMeta == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(newMeta));
        levels = newLevels == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(newLevels));
        xp = newXp == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(newXp));
    }

    public static int getState() { return state; }
    public static boolean isActive() { return state == STATE_ACTIVE; }

    /** 全部能力名（按服务端配置顺序） */
    public static java.util.List<String> getNames() {
        return new java.util.ArrayList<>(meta.keySet());
    }

    public static int getBaseLevel(String name) {
        int[] v = meta.get(name);
        return v == null ? 0 : v[0];
    }

    public static int getMaxLevel(String name) {
        int[] v = meta.get(name);
        return v == null ? 100 : v[1];
    }

    /** 当前等级；未显式存储过的能力返回其 baseLevel */
    public static int getLevel(String name) {
        Integer v = levels.get(name);
        return v != null ? v : getBaseLevel(name);
    }

    /** 已显式存储的等级原始表（未接触过的能力不含在内，调用方按 baseLevel 回落） */
    public static Map<String, Integer> getStoredLevels() {
        return levels;
    }

    public static double getXp(String name) {
        return xp.getOrDefault(name, 0.0);
    }

    /**
     * 从当前等级升到下一级所需经验：baseXp × (有效等级+1)^exponent。
     * 与服务端 ConfigLoader.internalXpRequired 同公式。
     */
    public static double xpRequired(String name, int currentLevel) {
        int effective = Math.max(0, currentLevel - getBaseLevel(name));
        return baseXp * Math.pow(effective + 1, exponent);
    }

    public static void clear() {
        state = STATE_DISABLED;
        baseXp = 100.0;
        exponent = 1.5;
        meta = Collections.emptyMap();
        levels = Collections.emptyMap();
        xp = Collections.emptyMap();
    }
}
