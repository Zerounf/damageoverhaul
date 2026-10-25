package com.zerochen.damageoverhaul.registry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ModComponents {
    public static final String DAMAGE_DISTRIBUTION_KEY = "damage_distribution";

    public static Map<String, Float> getDamageDistribution(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DAMAGE_DISTRIBUTION_KEY)) return null;
        CompoundTag dist = tag.getCompound(DAMAGE_DISTRIBUTION_KEY);
        if (dist.isEmpty()) return null;
        Map<String, Float> map = new HashMap<>();
        for (String key : dist.getAllKeys()) {
            map.put(key, dist.getFloat(key));
        }
        return map;
    }
}
