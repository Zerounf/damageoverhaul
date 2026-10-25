package com.zerochen.damageoverhaul.capability;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

/** 把 InternalAttributes 附加到玩家实体上的 Provider（NBT 持久化） */
public class InternalAttributesProvider implements ICapabilitySerializable<CompoundTag> {

    public static final ResourceLocation ID =
            new ResourceLocation(DamageOverhaulMod.MOD_ID, "internal_attributes");

    private final InternalAttributes backend = new InternalAttributes();
    private final LazyOptional<InternalAttributes> optional = LazyOptional.of(() -> backend);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        return cap == InternalAttributesCapability.INTERNAL ? optional.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        return backend.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        backend.deserializeNBT(nbt);
    }

    public void invalidate() {
        optional.invalidate();
    }
}
