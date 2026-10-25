package com.zerochen.damageoverhaul.capability;

import com.zerochen.damageoverhaul.config.ConfigLoader;
import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部属性系统的玩家数据（more_attributes 未安装时启用）。
 *
 * 存储两类数据：
 *   levels：能力名 → 当前等级（含 baseLevel，例如 strength 从 10 起步）
 *   xp：    能力名 → 当前等级内的经验进度（升级后扣除消耗）
 *
 * 有效属性点 = 等级 − baseLevel（与 more_attributes 缩放语义一致，
 * 内部系统下恒 ≥ 0）。
 */
public class InternalAttributes {

    private final Map<String, Integer> levels = new LinkedHashMap<>();
    private final Map<String, Double> xp = new LinkedHashMap<>();

    /** 当前等级；从未升级过的能力返回配置的 baseLevel */
    public int getLevel(String name) {
        Integer v = levels.get(name);
        return v != null ? v : ConfigLoader.getInternalBaseLevel(name);
    }

    public void setLevel(String name, int level) {
        levels.put(name, level);
    }

    public double getXp(String name) {
        return xp.getOrDefault(name, 0.0);
    }

    public void setXp(String name, double value) {
        xp.put(name, value);
    }

    /** 已显式存储的等级表（未接触过的能力不含在内，调用方按 baseLevel 处理） */
    public Map<String, Integer> getLevels() {
        return Collections.unmodifiableMap(levels);
    }

    public Map<String, Double> getXpMap() {
        return Collections.unmodifiableMap(xp);
    }

    public void copyFrom(InternalAttributes other) {
        this.levels.clear();
        this.levels.putAll(other.levels);
        this.xp.clear();
        this.xp.putAll(other.xp);
    }

    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();
        CompoundTag lv = new CompoundTag();
        for (Map.Entry<String, Integer> e : levels.entrySet()) lv.putInt(e.getKey(), e.getValue());
        CompoundTag xpTag = new CompoundTag();
        for (Map.Entry<String, Double> e : xp.entrySet()) xpTag.putDouble(e.getKey(), e.getValue());
        root.put("levels", lv);
        root.put("xp", xpTag);
        return root;
    }

    public void deserializeNBT(CompoundTag root) {
        levels.clear();
        xp.clear();
        if (root.contains("levels")) {
            CompoundTag lv = root.getCompound("levels");
            for (String key : lv.getAllKeys()) levels.put(key, lv.getInt(key));
        }
        if (root.contains("xp")) {
            CompoundTag xpTag = root.getCompound("xp");
            for (String key : xpTag.getAllKeys()) xp.put(key, xpTag.getDouble(key));
        }
    }
}
