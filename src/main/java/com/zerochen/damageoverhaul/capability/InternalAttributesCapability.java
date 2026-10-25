package com.zerochen.damageoverhaul.capability;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

/** 内部属性 Capability 持有者（more_attributes 未安装时作为补正数据源） */
public final class InternalAttributesCapability {

    private InternalAttributesCapability() {}

    public static final Capability<InternalAttributes> INTERNAL =
            CapabilityManager.get(new CapabilityToken<>() {});

    /** mod 总线 RegisterCapabilitiesEvent 中调用 */
    public static void register(RegisterCapabilitiesEvent event) {
        event.register(InternalAttributes.class);
    }
}
