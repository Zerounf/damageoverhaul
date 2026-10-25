package com.zerochen.damageoverhaul.event;

import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.ArmorResistance;
import com.zerochen.damageoverhaul.config.ConfigLoader.EntityResistance;
import com.zerochen.damageoverhaul.config.ConfigLoader.WeaponDistribution;
import com.zerochen.damageoverhaul.registry.ModComponents;
import com.zerochen.damageoverhaul.registry.ModEnchantments;
import com.zerochen.damageoverhaul.registry.ModMobEffects;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeProtectionEnchantment;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeProjectileStrikeEnchantment;
import com.zerochen.damageoverhaul.registry.ModEnchantments.DamageTypeStrikeEnchantment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class DamageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("DamageOverhaul|Damage");

    /**
     * 热路径调试日志：仅在 special_mechanics.json 的 enable_debug_logging=true 时输出。
     * 使用 Supplier 延迟构造消息，关闭时零开销（不拼字符串、不建流）。
     * 适用于每个射弹生成/每次伤害结算都会触发的日志；
     * 低频事件（自动学习、实体替换）仍直接用 LOGGER.info。
     */
    private static void hotLog(java.util.function.Supplier<String> msg) {
        if (ConfigLoader.isDebugLogging()) {
            LOGGER.info(msg.get());
        }
    }

    /** ThreadLocal 用于传递调整后的伤害值（LivingDamageEvent 之后不再被原版减免覆盖） */
    public static final ThreadLocal<Float> PENDING_LAST_DAMAGE = ThreadLocal.withInitial(() -> -1f);

    // 负抗性产生的治疗量（value<0 转治疗），延迟到 hurt() RETURN（伤害实际生效后）再恢复
    public static final ThreadLocal<Float> PENDING_HEALING = ThreadLocal.withInitial(() -> 0f);
    /**
     * 本模组不再使用「把射弹物品栈NBT保存到Projectile.persistentData」的旧链路。
     * 新版实现：
     *   1) 发射刚进世界时（EntityJoinLevelEvent）精准取出射弹对应物品（含附魔NBT）；
     *   2) 当场计算出最终完整伤害分布（基础分布 + 所有 projectile_strike 附魔 × bonus_levels 配置）；
     *   3) 通过 Mixin 字段写入射弹实例（MixinProjectileDamageDistribution#damageDistributionOverride）；
     *   4) 伤害结算时直接读取 Mixin 字段覆盖原结果，避免任何反射/背包扫描/重解析附魔。
     */

    /**
     * 射弹实体刚加入世界时（EntityJoinLevelEvent）：
     *
     * <h3>新版链路（100% 精准，不依赖反射/背包扫描匹配，完全不硬编码）</h3>
     * <ol>
     *   <li>优先通过「最精准的官方 API / 实体本身的字段」直接拿到发射瞬间的弹药物品栈（含完整附魔 NBT）：
     *     <ul>
     *       <li>ThrowableItemProjectile（雪球/鸡蛋/药水瓶）→ 实体自带 {@code getItem()}（真实栈，保留 NBT）</li>
     *       <li>AbstractArrow（箭/光谱箭/三叉戟）：
     *         <ul>
     *           <li>Crossbow场景：射手主/副手 Crossbow 的 {@code ChargedProjectiles} NBT 中直接读取（箭已在弩内）</li>
     *           <li>Bow/LivingEntity 通用场景：调用 MC 官方 {@link LivingEntity#getProjectile(ItemStack)}（即原版选弹逻辑），返回被消耗的那支弹药（100% 精确匹配）</li>
     *         </ul>
     *       </li>
     *     </ul>
     *   </li>
     *   <li>读取物品上所有 {@code projectile_strike_<damage_type>} 附魔（不互斥，允许多类型叠加）；若无则跳过。</li>
     *   <li>从 {@code ranged_config.json} 取「基础射弹分布」，再按 {@code ConfigLoader.getProjectileStrikeBonusLevels()}
     *       （来自 projectile_strike_config.json，完全配置化，无硬编码 0.1/级）叠加每种附魔的加成。</li>
     *   <li>通过 {@link MixinProjectileDamageDistribution}（@Mixin(Projectile.class) @Unique 字段）
     *       把最终完整伤害分布直接写入实体本身。</li>
     * </ol>
     * 伤害结算（{@link LivingDamageEvent}）时优先读取该 Mixin 字段覆盖，彻底跳过任何「反射 getPickupItem / 背包扫描匹配
     * / 重新解析附魔」的旧易失效链路。
     */
    @SubscribeEvent
    public void onProjectileJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Projectile projectile)) return;
        ResourceLocation projectileId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());

        Entity ownerEntity = projectile.getOwner();
        ItemStack projectileItem = ItemStack.EMPTY;
        String sourcePath = null;  // 用于日志：精准取弹来源

        // ========== Step 0: 实体替换（replace_entity=true 时，取消原实体并生成新实体） ==========
        // 检查射手持有的弹药物品是否有 replace_entity=true 的映射
        if (ownerEntity instanceof LivingEntity shooter) {
            // 尝试获取弹药物品ID
            ResourceLocation ammoItemId = null;
            // ThrowableItemProjectile：直接取实体自带的物品
            if (projectile instanceof ThrowableItemProjectile throwable) {
                ammoItemId = BuiltInRegistries.ITEM.getKey(throwable.getItem().getItem());
            }
            // AbstractArrow：通过 getProjectile 获取弹药
            else if (projectile instanceof AbstractArrow) {
                for (ItemStack hand : new ItemStack[]{shooter.getMainHandItem(), shooter.getOffhandItem()}) {
                    if (hand == null || hand.isEmpty()) continue;
                    Item handItem = hand.getItem();
                    if (!(handItem instanceof net.minecraft.world.item.BowItem
                            || handItem instanceof CrossbowItem
                            || handItem instanceof net.minecraft.world.item.ProjectileWeaponItem)) continue;
                    try {
                        ItemStack picked = shooter.getProjectile(hand);
                        if (picked != null && !picked.isEmpty()) {
                            ammoItemId = BuiltInRegistries.ITEM.getKey(picked.getItem());
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (ammoItemId != null) {
                ConfigLoader.ProjectileItemMapping mapping = ConfigLoader.getProjectileMappingForItem(ammoItemId);
                if (mapping != null && mapping.replaceEntity
                        && !mapping.entityId.equals(projectileId)
                        && ConfigLoader.isAllowDangerousReplace()) {
                    // 执行实体替换
                    EntityType<?> replacementType = BuiltInRegistries.ENTITY_TYPE.get(mapping.entityId);
                    if (replacementType != null) {
                        // 创建替换实体必须捕获 Throwable：目标实体类可能因模组版本不兼容等原因
                        // 抛出 NoClassDefFoundError 等 Error，此时保持原射弹继续正常结算
                        Entity replacement = null;
                        try {
                            replacement = replacementType.create(event.getLevel());
                        } catch (Throwable t) {
                            LOGGER.warn("[ProjectileReplace] 替换实体 {} 创建失败（{}: {}），保持原射弹 {}",
                                    mapping.entityId, t.getClass().getName(), t.getMessage(), projectileId);
                        }
                        if (replacement != null) {
                            // 转移位置、速度、旋转、Owner
                            replacement.moveTo(projectile.getX(), projectile.getY(), projectile.getZ(),
                                    projectile.getYRot(), projectile.getXRot());
                            replacement.setDeltaMovement(projectile.getDeltaMovement());
                            if (replacement instanceof Projectile projReplacement) {
                                projReplacement.setOwner(ownerEntity);
                            }
                            // 用 NBT 标记此实体是本mod替换的射弹（伤害结算时用此标记走分布系统）
                            CompoundTag tag = replacement.getPersistentData();
                            tag.putBoolean("damageoverhaul.replaced_projectile", true);
                            tag.putString("damageoverhaul.original_item", ammoItemId.toString());

                            event.getLevel().addFreshEntity(replacement);
                            event.setCanceled(true);
                            LOGGER.info("[ProjectileReplace] {}({}) → {} (replace_entity=true, dangerous_replace=true)",
                                    ammoItemId, projectileId, mapping.entityId);
                            return;
                        }
                    }
                } else if (mapping != null && mapping.replaceEntity && !ConfigLoader.isAllowDangerousReplace()) {
                    LOGGER.warn("[ProjectileReplace] {} → {} 映射标记 replace_entity=true，但 allow_dangerous_replace=false，跳过替换",
                            ammoItemId, mapping.entityId);
                }
            }
        }

        hotLog(() -> "[ProjectileVariant] 触发检查: 实体=" + projectileId + " 射手=" +
                (ownerEntity == null ? "null" : ownerEntity.getClass().getSimpleName()) + " 射手类型=" +
                (ownerEntity == null ? "null" : BuiltInRegistries.ENTITY_TYPE.getKey(ownerEntity.getType())));

        // ========== Step 1: 精准获取弹药物品栈（含附魔 NBT） ==========

        // Case A：ThrowableItemProjectile（雪球/鸡蛋/药水瓶/末影珍珠 等）
        //   实体本身 entityData 里就存了真实 ItemStack（ItemStack#copyWithCount 保留 NBT），100% 准确。
        if (projectile instanceof ThrowableItemProjectile throwable) {
            projectileItem = throwable.getItem();
            sourcePath = "ThrowableItemProjectile#getItem()";
        }

        // Case B：AbstractArrow（箭/光谱箭/三叉戟/模组 javelin 等）
        //   需要结合射手（LivingEntity）的持物来还原射出的弹药真实栈。
        else if (projectile instanceof AbstractArrow abstractArrow && ownerEntity instanceof LivingEntity shooter) {
            // B-1 Crossbow 场景（优先级最高）：箭已经在弩的 ChargedProjectiles NBT 里，最准
            ItemStack mainHand = shooter.getMainHandItem();
            ItemStack offHand = shooter.getOffhandItem();
            hotLog(() -> "[ProjectileVariant] Case B: AbstractArrow 射手主手=" +
                    ((mainHand == null || mainHand.isEmpty()) ? "EMPTY" : BuiltInRegistries.ITEM.getKey(mainHand.getItem())) +
                    " 副手=" + ((offHand == null || offHand.isEmpty()) ? "EMPTY" : BuiltInRegistries.ITEM.getKey(offHand.getItem())));
            crossbowScan:
            for (ItemStack hand : new ItemStack[]{mainHand, offHand}) {
                if (hand == null || hand.isEmpty()) continue;
                if (!(hand.getItem() instanceof CrossbowItem)) continue;
                CompoundTag tag = hand.getTag();
                if (tag == null || !tag.contains("ChargedProjectiles", 9)) continue;
                ListTag charged = tag.getList("ChargedProjectiles", 10);
                for (int i = 0; i < charged.size(); i++) {
                    ItemStack cp = ItemStack.of(charged.getCompound(i));
                    if (cp == null || cp.isEmpty()) continue;
                    // 选第一个：含有至少 1 级 projectile_strike 附魔的箭
                    // 注：isPure 判定已在铁砧合并阶段完成，取弹阶段不再重复检查（能附上魔的箭必然是纯射弹）
                    if (ModEnchantments.countProjectileStrikeEnchantments(cp) > 0) {
                        projectileItem = cp;
                        sourcePath = "CrossbowItem.ChargedProjectiles(NBT)";
                        break crossbowScan;
                    }
                }
            }

            // B-2 Bow/LivingEntity 通用场景：调用 MC 官方 API getProjectile(weapon)
            //   → 返回该武器"当前使用中选出的弹药"（也就是原版会被 shrink(1) 消耗的那支）
            //   对 Bow 最准，Crossbow 也兼容（charged 时仍能返回弹药）。
            if ((projectileItem == null || projectileItem.isEmpty())) {
                for (ItemStack hand : new ItemStack[]{mainHand, offHand}) {
                    if (hand == null || hand.isEmpty()) continue;
                    Item handItem = hand.getItem();
                    // BowItem / CrossbowItem：两者都是 launch 投射物的工具武器，都有 getProjectile 语义
                    if (!(handItem instanceof net.minecraft.world.item.BowItem
                            || handItem instanceof CrossbowItem
                            || handItem instanceof net.minecraft.world.item.ProjectileWeaponItem)) continue;
                    try {
                        ItemStack picked = shooter.getProjectile(hand);
                        int encCnt = ModEnchantments.countProjectileStrikeEnchantments(picked);
                        boolean isPure = ModEnchantments.isPureProjectileItem(picked);
                        hotLog(() -> "[ProjectileVariant] B-2: getProjectile(" + BuiltInRegistries.ITEM.getKey(handItem) +
                                ") → 弹药=" + ((picked == null || picked.isEmpty()) ? "EMPTY" : BuiltInRegistries.ITEM.getKey(picked.getItem())) +
                                " isPure=" + isPure + " 附魔数=" + encCnt);
                        if (picked != null && !picked.isEmpty()
                                && encCnt > 0) {
                            projectileItem = picked;
                            sourcePath = "LivingEntity.getProjectile("
                                    + BuiltInRegistries.ITEM.getKey(handItem) + ")";
                            break;
                        }
                    } catch (Exception ignored) {
                        // 某些模组自定义武器可能抛异常，安全跳过
                    }
                }
            }

            // B-3 最后兜底（非必须，但对玩家主手直接 throw 物品等场景）：
            //   遍历射手物品栏（含热栏+主背包）找第一个「纯射弹物品 + 有 projectile_strike 附魔」的栈
            if ((projectileItem == null || projectileItem.isEmpty()) && shooter instanceof Player player) {
                Inventory inv = player.getInventory();
                for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                    ItemStack s = inv.getItem(slot);
                    if (s == null || s.isEmpty()) continue;
                    if (ModEnchantments.countProjectileStrikeEnchantments(s) > 0) {
                        projectileItem = s;
                        sourcePath = "Inventory(slot=" + slot + " 兜底)";
                        break;
                    }
                }
            }
        }

        // ========== Step 2: 解析附魔 → 计算最终分布 → 写入 Mixin 字段 ==========

        // 没拿到任何符合条件的弹药物品（没附魔 / 或非纯射弹）→ 跳过，保留原版基础分布
        if (projectileItem == null || projectileItem.isEmpty()
                || ModEnchantments.countProjectileStrikeEnchantments(projectileItem) <= 0) {
            final String spRef = sourcePath;
            final ItemStack piRef = projectileItem;
            hotLog(() -> "[ProjectileVariant] 未命中附魔: 实体=" + projectileId + " 取弹来源=" +
                    (spRef == null ? "N/A" : spRef) + " 弹药=" +
                    ((piRef == null || piRef.isEmpty()) ? "EMPTY" : BuiltInRegistries.ITEM.getKey(piRef.getItem())));
            return;
        }

        // 取所有 projectile_strike 附魔（多类型叠加，不再互斥）
        Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(projectileItem);
        Map<String, Float> bonusCfg = ConfigLoader.getProjectileStrikeBonusLevels();
        Map<String, Float> applied = new LinkedHashMap<>();  // 日志：每个附魔的应用情况

        // 1) 基础分布：来自 ranged_config.json（若未配置则空）
        Map<String, Float> baseDist = ConfigLoader.getProjectileDistribution(projectileId);
        Map<String, Float> finalDist = new LinkedHashMap<>((baseDist != null) ? baseDist : Map.of());

        // 2) 叠加每种 projectile_strike 附魔：取 bonus_levels 对应等级的加成数值（配置化，不硬编码 0.1/级）
        for (Map.Entry<String, RegistryObject<DamageTypeProjectileStrikeEnchantment>> entry
                : ModEnchantments.PROJECTILE_STRIKE_ENCHANTMENTS.entrySet()) {
            String type = entry.getKey();
            DamageTypeProjectileStrikeEnchantment enc = entry.getValue().orElse(null);
            if (enc == null) continue;
            int level = enchants.getOrDefault(enc, 0);
            if (level <= 0) continue;
            String lvStr = Integer.toString(level);
            Float bonus = bonusCfg.get(lvStr);
            if (bonus == null || bonus <= 0) {
                applied.put(type + "_Lv" + level, 0f);
                continue;
            }
            finalDist.merge(type, bonus, Float::sum);
            applied.put(type + "_Lv" + level, Math.round(bonus * 1000f) / 1000f);
        }

        // 3) 写入射弹的 Mixin 字段（运行时绑定，Mixin 在 Projectile 中注入字段，javac 静态看不见继承，因此走反射式工具方法）
        if (!finalDist.isEmpty() && ProjectileOverrideHelper.setOverride(projectile, finalDist)) {
            final ItemStack shotItemRef = projectileItem;
            final String srcRef = sourcePath;
            hotLog(() -> "[ProjectileVariant] OK → 实体=" + projectileId +
                    ", 弹药=" + BuiltInRegistries.ITEM.getKey(shotItemRef.getItem()) +
                    ", 取弹来源=" + srcRef + ", 附魔加成=" + applied +
                    ", 最终覆盖分布=" + finalDist.entrySet().stream()
                            .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                            .map(e -> String.format("%s=%.2f", e.getKey(), e.getValue()))
                            .collect(Collectors.joining(", ", "{", "}")));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDamage(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();

        Map<String, Float> damage = tryResolveDamageDistribution(target, source);
        if (damage == null || damage.isEmpty()) return;

        applyModifiedDamage(event, target, damage);
    }

    // ======================== 伤害分布解析（武器附魔仍在此；射弹附魔已在发射时预计算并存Mixin字段） ========================
    private Map<String, Float> tryResolveDamageDistribution(LivingEntity target, DamageSource source) {
        Entity sourceEntity = source.getEntity();
        Entity directEntity = source.getDirectEntity();
        boolean isRanged = (sourceEntity != null && directEntity != null && sourceEntity != directEntity);
        Map<String, Float> damage = null;
        ItemStack attackerWeapon = null;
        boolean usedProjectileSource = false; // 记录本次远程是否走射弹来源分支

        // Step 0: 替换实体检测（NBT标记 damageoverhaul.replaced_projectile）
        // 如果 directEntity 是被本mod替换的射弹（如箭→TNT），使用映射的实体ID查询伤害分布
        if (directEntity != null) {
            CompoundTag entityTag = directEntity.getPersistentData();
            if (entityTag.getBoolean("damageoverhaul.replaced_projectile")) {
                String originalItemStr = entityTag.getString("damageoverhaul.original_item");
                try {
                    ResourceLocation originalItemId = new ResourceLocation(originalItemStr);
                    ConfigLoader.ProjectileItemMapping mapping = ConfigLoader.getProjectileMappingForItem(originalItemId);
                    if (mapping != null) {
                        // 优先使用替换实体在 PROJECTILE_CACHE 中配置的分布
                        Map<String, Float> replacedDist = ConfigLoader.getProjectileDistribution(mapping.entityId);
                        if (replacedDist != null && !replacedDist.isEmpty()) {
                            damage = new LinkedHashMap<>(replacedDist);
                            LOGGER.info("[ReplacedProjectile] 原物品={} → 替换实体={} → 使用PROJECTILE_CACHE分布={}",
                                    originalItemId, mapping.entityId, formatDist(damage));
                        } else {
                            // 无 PROJECTILE_CACHE 分布 → 走 damage_type_mappings 兜底（如 TNT爆炸→blunt）
                            LOGGER.info("[ReplacedProjectile] 原物品={} → 替换实体={} 无PROJECTILE_CACHE分布，走damage_type_mappings兜底",
                                    originalItemId, mapping.entityId);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ReplacedProjectile] 解析替换实体标记失败: original_item={}", originalItemStr);
                }
            }
        }

        // 1. 远程攻击（仅当Step 0未解析到分布时进入）
        if (damage == null && isRanged) {
            ResourceLocation projectileId = BuiltInRegistries.ENTITY_TYPE.getKey(directEntity.getType());
            // 根据射弹配置决定伤害来源（支持 follow_default=true/false）
            String actualSource = ConfigLoader.getRangedDamageSourceForProjectile(projectileId);
            hotLog(() -> "[Ranged] 射弹=" + projectileId + " → 判定伤害来源=" + actualSource +
                    "（射手=" + (sourceEntity == null ? "null" : sourceEntity.getType()) + "）");
            if ("projectile".equals(actualSource)) {
                usedProjectileSource = true;

                // ========== 优先级最高：直接读射弹实例 Mixin 预存分布（发射时已算好） ==========
                // 这是新版链路：EntityJoinLevelEvent 时直接算出最终分布写入实体，
                // 完全绕过「反射/物品栈/重新解析附魔」的易失效链路。
                boolean hitOverride = false;
                if (directEntity instanceof Projectile) {
                    Map<String, Float> override = ProjectileOverrideHelper.getOverride(directEntity);
                    if (override != null && !override.isEmpty()) {
                        damage = new LinkedHashMap<>(override);
                        hitOverride = true;
                        final Map<String, Float> overrideRef = damage;
                        hotLog(() -> "[Ranged] ✅ 命中射弹 Mixin 预存分布 override：" + formatDist(overrideRef));
                    }
                }

                // Fallback（没命中 override：射弹无附魔或新版链路未生效）→ 使用原版基础射弹分布
                if (!hitOverride) {
                    damage = tryGetProjectileDistribution(directEntity);
                    final Map<String, Float> baseRef = damage;
                    hotLog(() -> "[Ranged] 使用射弹基础分布（无附魔 override）：" + formatDist(baseRef));
                }

                // ========== 通用回退：来源武器分布（枪械类模组兼容，如 Scorched Guns / TACZ） ==========
                // 枪械模组的伤害由射弹结算（damage type 如 scguns:bullet/tacz:bullet），
                // 射弹实体ID与枪物品ID不同名且非 AbstractArrow/ThrowableItemProjectile，
                // 无法走射弹分布链路 → 回退到「射手主手/副手武器」查询 weapon_distributions.json。
                // 完全通用：任何"枪JSON存伤害+射弹结算"型模组，只要在 weapon_distributions.json
                // 中配置了枪械物品条目即生效（配合自动学习无需手动添加）。
                if ((damage == null || damage.isEmpty()) && sourceEntity instanceof LivingEntity shooter) {
                    for (ItemStack hand : new ItemStack[]{shooter.getMainHandItem(), shooter.getOffhandItem()}) {
                        if (hand == null || hand.isEmpty()) continue;
                        ResourceLocation handItemId = BuiltInRegistries.ITEM.getKey(hand.getItem());
                        Map<String, Float> weaponDist = ModComponents.getDamageDistribution(hand);
                        if (weaponDist == null || weaponDist.isEmpty()) {
                            ConfigLoader.WeaponDistribution wd = ConfigLoader.getWeaponDistribution(handItemId);
                            if (wd != null && wd.damageDistribution != null) {
                                weaponDist = wd.damageDistribution;
                            }
                        }
                        if (weaponDist != null && !weaponDist.isEmpty()) {
                            damage = new LinkedHashMap<>(weaponDist);
                            attackerWeapon = hand; // 让后续 strike 附魔在武器分布上生效
                            final Map<String, Float> wdRef = damage;
                            hotLog(() -> "[Ranged] 来源武器回退命中: 射弹=" + projectileId +
                                    " → 武器=" + handItemId + " 分布=" + formatDist(wdRef));
                            break;
                        }
                    }

                    // 自动学习：所有链路都无分布 → 为来源武器添加默认条目（穿刺100%）并写入配置
                    if ((damage == null || damage.isEmpty()) && ConfigLoader.isAutoLearnRangedWeapons()) {
                        for (ItemStack hand : new ItemStack[]{shooter.getMainHandItem(), shooter.getOffhandItem()}) {
                            if (hand == null || hand.isEmpty()) continue;
                            ResourceLocation handItemId = BuiltInRegistries.ITEM.getKey(hand.getItem());
                            if (ConfigLoader.addLearnedWeapon(handItemId)) {
                                ConfigLoader.WeaponDistribution wd = ConfigLoader.getWeaponDistribution(handItemId);
                                if (wd != null && wd.damageDistribution != null) {
                                    damage = new LinkedHashMap<>(wd.damageDistribution);
                                    attackerWeapon = hand;
                                    LOGGER.info("[AutoLearn] 远程武器 {} 首次结算，已自动添加默认分布（穿刺100%）",
                                            handItemId);
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                if (sourceEntity instanceof Player player) {
                    attackerWeapon = player.getMainHandItem();
                    damage = ModComponents.getDamageDistribution(attackerWeapon);
                    if (damage == null || damage.isEmpty()) {
                        damage = tryGetWeaponDistribution(attackerWeapon);
                    }
                } else if (sourceEntity instanceof LivingEntity livingSource) {
                    attackerWeapon = livingSource.getMainHandItem();
                    damage = tryGetWeaponDistribution(attackerWeapon);
                }
                final Map<String, Float> dmgRef = damage;
                final ItemStack weaponRef = attackerWeapon;
                hotLog(() -> "[Ranged] 使用武器分布：" + formatDist(dmgRef) + "，武器=" +
                        (weaponRef == null || weaponRef.isEmpty() ? "EMPTY" : weaponRef.getItem()) +
                        "，武器附魔总等级(Strike)=" + countWeaponStrikeEnchantments(weaponRef));
            }
        } else if (damage == null) {
            // 近战（仅当Step 0未解析到分布时进入）
            if (sourceEntity instanceof Player player) {
                attackerWeapon = player.getMainHandItem();
                damage = ModComponents.getDamageDistribution(attackerWeapon);
                if (damage == null || damage.isEmpty()) {
                    damage = tryGetWeaponDistribution(attackerWeapon);
                }
            } else if (sourceEntity instanceof LivingEntity livingSource) {
                attackerWeapon = livingSource.getMainHandItem();
                damage = tryGetWeaponDistribution(attackerWeapon);
            }
        }

        // 3. 空手分布
        if (damage == null || damage.isEmpty()) {
            if (sourceEntity != null) {
                ResourceLocation sourceId = BuiltInRegistries.ENTITY_TYPE.getKey(sourceEntity.getType());
                ConfigLoader.WeaponDistribution ud = (sourceId != null) ? ConfigLoader.getUnarmedDistribution(sourceId) : null;
                if (ud != null) {
                    damage = ud.damageDistribution;
                }
            }
        }

        // 3.5 方块源性伤害分布（block_distributions.json，如岩浆块/篝火/仙人掌/细雪）
        // 判定特征：directEntity 与 sourceEntity 均为 null（hot_floor/in_fire站火/cactus/sweet_berry_bush/freeze 等
        // 均由方块类内部直接 hurt(damageSources().xxx())，无实体来源；火焰附加等有攻击者的不算）
        if ((damage == null || damage.isEmpty()) && sourceEntity == null && directEntity == null) {
            net.minecraft.core.BlockPos basePos = target.blockPosition();
            // 候选方块：身体所在格（entityInside 类：篝火/火/仙人掌/浆果丛/细雪/凋零玫瑰）优先，其次脚下方块（stepOn 类：岩浆块）
            net.minecraft.world.level.block.state.BlockState[] candidates = new net.minecraft.world.level.block.state.BlockState[]{
                    target.level().getBlockState(basePos),
                    target.level().getBlockState(basePos.below())
            };
            for (net.minecraft.world.level.block.state.BlockState state : candidates) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (blockId == null) continue;
                ConfigLoader.BlockDistEntry entry = ConfigLoader.getBlockDistEntry(blockId);
                if (entry == null) {
                    // 自动学习：无条目 → 生成 use_default=true 条目（行为不变，供用户改为 false 并自定义分布）
                    if (ConfigLoader.isAutoLearnBlockDamage() && ConfigLoader.addLearnedBlock(blockId)) {
                        LOGGER.info("[BlockDist] 自动学习方块源性伤害方块: {}（use_default=true）", blockId);
                    }
                    continue;
                }
                if (!entry.useDefault && entry.damageDistribution != null && !entry.damageDistribution.isEmpty()) {
                    damage = new LinkedHashMap<>(entry.damageDistribution);
                    Map<String, Float> finalDamageRef = damage;
                    hotLog(() -> "[BlockDist] 方块 " + blockId + " 接管伤害: " + formatDist(finalDamageRef));
                    break;
                }
            }
        }

        // 4. 伤害类型映射（支持混合分布）
        if (damage == null || damage.isEmpty()) {
            Map<String, Float> mappedDist = ConfigLoader.getMappedDamageDistribution(source);
            if (mappedDist != null && !mappedDist.isEmpty()) {
                damage = new LinkedHashMap<>(mappedDist);
            }
        }

        // 5. 近战默认钝击
        if (damage == null || damage.isEmpty()) {
            if (sourceEntity != null && sourceEntity == directEntity) {
                damage = new HashMap<>();
                damage.put("blunt", 1.0f);
            }
        }

        // 6. 附魔加成
        // 注：射弹 ProjectileStrike 附魔不再在此处应用（已在 EntityJoinLevelEvent 时合并进入 override 分布，避免重复加算）。
        //     此处仅保留「武器 Strike 附魔」（近战 + 远程走武器分支时生效）。
        if (damage != null && !damage.isEmpty()) {
            if (attackerWeapon != null && !attackerWeapon.isEmpty()) {
                // applyWeaponStrikeEnchantments 不修改入参（返回新 map），直接引用原 map 作 before，避免每次伤害白复制一份
                Map<String, Float> beforeRef = damage;
                damage = applyWeaponStrikeEnchantments(damage, attackerWeapon);
                Map<String, Float> afterRef = damage;
                hotLog(() -> "[Enchant] before=" + formatDist(beforeRef) + ", after=" + formatDist(afterRef) +
                        ", appliedWeaponStrike=true" +
                        ", appliedProjectileStrike=SKIPPED(已合并入override分布)");
            }
        }

        return damage;
    }

    /** 武器 Strike 附魔：不降低其他占比，直接增加对应伤害类型占比 0.1/级（满级4级+0.4） */
    private Map<String, Float> applyWeaponStrikeEnchantments(Map<String, Float> damage, ItemStack weapon) {
        Map<String, Float> result = new HashMap<>(damage);
        Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(weapon);

        for (Map.Entry<String, RegistryObject<DamageTypeStrikeEnchantment>> entry
                : ModEnchantments.STRIKE_ENCHANTMENTS.entrySet()) {
            String type = entry.getKey();
            DamageTypeStrikeEnchantment ench = entry.getValue().orElse(null);
            if (ench == null) continue;
            int level = enchants.getOrDefault(ench, 0);
            if (level <= 0) continue;

            float bonus = ench.getRatioPerLevel() * level;
            result.merge(type, bonus, Float::sum);
        }

        return result;
    }

    private Map<String, Float> tryGetProjectileDistribution(Entity projectile) {
        if (projectile == null) return null;
        ResourceLocation projId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        if (projId == null) return null;
        return ConfigLoader.getProjectileDistribution(projId);
    }

    /** 伤害分布格式化辅助（便于日志） */
    private static String formatDist(Map<String, Float> damage) {
        if (damage == null) return "null";
        if (damage.isEmpty()) return "{}";
        return damage.entrySet().stream()
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .map(e -> String.format("%s=%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /** 统计某物品上所有武器 strike 附魔等级之和 */
    private static int countWeaponStrikeEnchantments(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return 0;
        int c = 0;
        Map<Enchantment, Integer> em = EnchantmentHelper.getEnchantments(weapon);
        for (var entry : ModEnchantments.STRIKE_ENCHANTMENTS.entrySet()) {
            Enchantment enc = entry.getValue().get();
            c += em.getOrDefault(enc, 0);
        }
        return c;
    }

    // ======================== 伤害计算（含附魔抗性阶段） ========================
    private void applyModifiedDamage(LivingHurtEvent event, LivingEntity target, Map<String, Float> damage) {
        float original = event.getAmount();
        ResourceLocation targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        EntityResistance baseResistance = ConfigLoader.getResistance(targetId);
        Map<String, Float> armorResistance = getArmorResistance(target);
        Map<String, Float> enchantResistance = getEnchantmentProtection(target);

        float armorValue = target.getArmorValue();
        float finalDamage = 0;
        float healing = 0;

        // 饰品效果（攻击端 + 防御端）：Curios 饰品框架（自家饰品 + 第三方饰品合并结算，同类取最高）
        //   攻击端：source 是玩家 → 遍历其饰品，按 damage_type 取最高增伤比例，乘入 typeDamage
        //   防御端：target 是玩家 → 遍历其饰品，返回各类型的最高抗性，按类型独立减免（仅对配置的类型生效）
        net.minecraft.world.entity.Entity sourceEntity = event.getSource().getEntity();
        // 三元 + final 单次赋值，使下方 hotLog lambda 能安全捕获（effectively final）
        final java.util.Map<String, Float> attackBonuses = (sourceEntity instanceof net.minecraft.world.entity.player.Player attackerPlayer)
                ? CharmEffectHandler.getAttackBonuses(attackerPlayer, damage.keySet())
                : java.util.Collections.emptyMap();
        final java.util.Map<String, Float> charmResistances = (target instanceof net.minecraft.world.entity.player.Player defenderPlayer)
                ? CharmEffectHandler.getDefenseResistances(defenderPlayer, damage.keySet())
                : java.util.Collections.<String, Float>emptyMap();

        // 药水效果（mob_effects_config.json 驱动，N类型×4效果）：
        //   攻击端：source 是 LivingEntity（含怪物）→ amplify_/weaken_<type> 增减其造成的对应类型伤害（加性并入饰品增伤乘区）
        //   防御端：target → resist_/vulnerable_<type> 增减该类型抗性（百分比，加性并入附魔抗性池，超100%同样触发治疗特性）
        final java.util.Map<String, Float> potionDamageMods = (sourceEntity instanceof LivingEntity livingSource)
                ? ModMobEffects.getTypeDamageModifier(livingSource)
                : java.util.Collections.<String, Float>emptyMap();
        final java.util.Map<String, Float> potionResistMods = ModMobEffects.getTypeResistanceModifier(target);

        // 装备补正（weapon_scaling.json / armor_scaling.json，软依赖 more_attributes 属性点；属性点=已投−baseLevel，可负）：
        //   武器（攻击端，仅玩家，按手持武器）：各类型补正加性并入攻击乘区（饰品+药水+武器补正）
        //   盔甲（防御端，仅玩家，4件）：每件各类型独立补正，逐件连乘 ∏(1−单件补正)，夹在附魔+药水抗性之后、饰品抗性之前
        final ItemStack scalingWeaponRef;
        final java.util.Map<String, Float> weaponScalingBonuses;
        if (ConfigLoader.isWeaponScalingEnabled()
                && com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isScalingActive()
                && sourceEntity instanceof net.minecraft.world.entity.player.Player scalingAttacker) {
            // applyModifiedDamage 不持有上游解析的武器，这里按「主手有补正条目→主手，否则副手→主手兜底」解析
            ItemStack mainHand = scalingAttacker.getMainHandItem();
            ItemStack offHand = scalingAttacker.getOffhandItem();
            ResourceLocation mainId = (mainHand == null || mainHand.isEmpty()) ? null
                    : BuiltInRegistries.ITEM.getKey(mainHand.getItem());
            ResourceLocation offId = (offHand == null || offHand.isEmpty()) ? null
                    : BuiltInRegistries.ITEM.getKey(offHand.getItem());
            boolean mainHas = mainId != null && ConfigLoader.getWeaponScalingRow(mainId.toString()) != null;
            boolean offHas = offId != null && ConfigLoader.getWeaponScalingRow(offId.toString()) != null;
            scalingWeaponRef = mainHas ? mainHand : (offHas ? offHand : mainHand);
            ConfigLoader.ScalingRow wrow = scalingWeaponRef == null || scalingWeaponRef.isEmpty() ? null
                    : ConfigLoader.getWeaponScalingRow(
                            BuiltInRegistries.ITEM.getKey(scalingWeaponRef.getItem()).toString());
            weaponScalingBonuses = wrow == null ? java.util.Collections.emptyMap()
                    : ConfigLoader.computeCorrections(wrow,
                            com.zerochen.damageoverhaul.integration.MoreAttributesBridge.getEffectivePoints(scalingAttacker));
        } else {
            scalingWeaponRef = ItemStack.EMPTY;
            weaponScalingBonuses = java.util.Collections.emptyMap();
        }

        final java.util.Map<String, Float> armorScalingFactors = new java.util.HashMap<>();
        if (ConfigLoader.isArmorScalingEnabled()
                && com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isScalingActive()
                && target instanceof net.minecraft.world.entity.player.Player scalingDefender) {
            java.util.Map<String, Integer> points =
                    com.zerochen.damageoverhaul.integration.MoreAttributesBridge.getEffectivePoints(scalingDefender);
            if (!points.isEmpty()) {
                for (EquipmentSlot slot : new EquipmentSlot[]{
                        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack armorStack = scalingDefender.getItemBySlot(slot);
                    if (armorStack == null || armorStack.isEmpty()) continue;
                    ResourceLocation aid = BuiltInRegistries.ITEM.getKey(armorStack.getItem());
                    ConfigLoader.ScalingRow arow = aid == null ? null : ConfigLoader.getArmorScalingRow(aid.toString());
                    if (arow == null) continue;
                    java.util.Map<String, Float> piece = ConfigLoader.computeCorrections(arow, points);
                    for (java.util.Map.Entry<String, Float> pe : piece.entrySet()) {
                        // 逐件连乘：本件系数 (1−补正) 并入该类型累计系数
                        armorScalingFactors.merge(pe.getKey(), 1f - pe.getValue(), (a, b) -> a * b);
                    }
                }
            }
        }

        hotLog(() -> "[DamageCalc] 目标=" + targetId + " 原伤害=" + original + ", 输入分布=" + formatDist(damage) +
                ", 攻击饰品加成=" + attackBonuses + ", 饰品类型抗性=" + charmResistances +
                ", 药水增伤=" + potionDamageMods + ", 药水抗性修正=" + potionResistMods +
                ", 武器补正=" + weaponScalingBonuses + ", 盔甲补正系数=" + armorScalingFactors);

        for (Map.Entry<String, Float> entry : damage.entrySet()) {
            String type = entry.getKey();
            float ratio = entry.getValue();
            // 饰品攻击加成 + 药水伤害增减 + 武器能力补正：本类型按合并乘区提升（修正可为负，clamp 防负系数）
            float attackBonus = attackBonuses.getOrDefault(type, 0f);
            float potionBonus = potionDamageMods.getOrDefault(type, 0f);
            float weaponScaling = weaponScalingBonuses.getOrDefault(type, 0f);
            float typeDamage = original * ratio * Math.max(0f, 1 + attackBonus + potionBonus + weaponScaling);

            // 阶段1：生物自带抗性减免
            float baseResist = (baseResistance == null) ? 0 : baseResistance.modifiers.getOrDefault(type, 0f);
            float A = typeDamage * (1 - baseResist / 100f);

            // 弹开判定
            if (ConfigLoader.isDeflectionEnabled() && ConfigLoader.isDeflectionAffected(type) && A > 0) {
                float Y = armorValue / A;
                float X = ConfigLoader.getDeflectionX();
                if (Y >= X) {
                    float chance = (X / Y) * ConfigLoader.getDeflectionZ();
                    if (target.getRandom().nextFloat() < chance) {
                        hotLog(() -> "[DamageCalc] 类型=" + type + " 被弹开（miss），原伤害=" + typeDamage);
                        continue;
                    }
                }
            }

            // 阶段2：护甲抗性减免
            float armorResist = armorResistance.getOrDefault(type, 0f);
            float B = A * (1 - armorResist / 100f);

            // 阶段2.5：附魔抗性 + 药水抗性修正（同一加性池，百分比）  Y = X × (1 - Z)  Z=合并抗性%
            float enchantResist = enchantResistance.getOrDefault(type, 0f);
            float potionResist = potionResistMods.getOrDefault(type, 0f);
            float afterEnchant = B * (1 - (enchantResist + potionResist) / 100f);

            // 阶段2.55：盔甲能力补正（4件逐件连乘 ∏(1−单件补正)，more_attributes 属性点驱动；可负→受伤增加）
            float armorScalingFactor = armorScalingFactors.getOrDefault(type, 1f);
            float afterArmorScaling = afterEnchant * armorScalingFactor;

            // 阶段2.6：饰品类型抗性减免（同类取最高已在上游合并；仅对配置的类型生效，负值不生效）
            float charmResist = Math.max(0f, charmResistances.getOrDefault(type, 0f));
            final float value = afterArmorScaling * (1 - charmResist);

            hotLog(() -> "[DamageCalc]   " + String.format("%-10s", type) +
                    ": ratio=" + String.format("%.2f", ratio) +
                    "×原伤=" + String.format("%.2f", typeDamage) +
                    "(药水" + String.format("%+.2f", potionBonus) +
                    "/武器补正" + String.format("%+.2f", weaponScaling) + ")" +
                    " → 自抗后" + String.format("%.2f", A) +
                    " → 甲抗后" + String.format("%.2f", B) +
                    " → 附魔+药水抗后" + String.format("%.2f", afterEnchant) +
                    "(附魔" + String.format("%.1f", enchantResist) + "%+药水" + String.format("%+.1f", potionResist) + "%)" +
                    " → 盔甲补正后" + String.format("%.2f", afterArmorScaling) +
                    "(×" + String.format("%.3f", armorScalingFactor) + ")" +
                    " → 饰品抗后" + String.format("%.2f", value));

            if (value >= 0) {
                finalDamage += value;
            } else {
                healing += -value;
            }
        }

        // 饰品防御端已改为阶段2.6按类型独立减免（仅对配置的类型生效，不再全局最终乘算）

        final float finalDmgRef = finalDamage;
        final float healRef = healing;
        hotLog(() -> "[DamageCalc] → 调整后伤害=" + String.format("%.2f", finalDmgRef) +
                ", 治疗量=" + String.format("%.2f", healRef) +
                "（原为" + String.format("%.2f", original) + "）");

        // ★ 改用 setAmount：不取消事件，让原版 actuallyHurt 流程继续执行。
        // 完整结算链变为：
        //   调整后伤害 = 原伤 ×(1+饰品增伤+药水增伤+武器补正) ×(1-生物抗性)×(1-盔甲抗性)×(1-附魔抗性-药水抗性)×盔甲补正连乘∏(1−单件补正)×(1-饰品类型抗性)   ← 本方法计算，setAmount
        //              → ×(1-原版护甲值抗性)                            ← 原版 getDamageAfterArmorAbsorb
        //              → 原版保护附魔减免（protect_<type> 未实现 getDamageProtection，不参与，无双重减免）
        //              → LivingDamageEvent 正常触发                      ← 传说生存等模组依赖此事件
        //              → 原版扣血/击退/受击动画/音效
        // 旧版（setCanceled + 手动 setHealth）会跳过以上全部原版流程，
        // 导致依赖原版伤害事件的模组（传说生存肢体受损等）全部失效。
        event.setAmount(Math.max(0f, finalDamage));

        // Mixin 在 hurt() RETURN 时覆盖 lastDamage=0，使后续伤害不被 invulnerableTime 吞掉
        PENDING_LAST_DAMAGE.set(0f);

        // 负抗性带来的治疗部分：延迟到 hurt() RETURN（伤害实际生效后）再恢复，
        // 避免在事件中 heal 后立刻被原版扣血抵消
        if (healing > 0) {
            PENDING_HEALING.set(healing);
        }

        // 冲击力/韧性系统：伤害结算后检查削韧值是否超过目标总韧性
        ImpactEffectHandler.checkImpact(target, event.getSource());
    }

    /** 计算护甲附魔提供的各伤害类型抗性（每级 +5%） */
    private Map<String, Float> getEnchantmentProtection(LivingEntity target) {
        Map<String, Float> total = new HashMap<>();

        for (ItemStack armorSlot : target.getArmorSlots()) {
            if (armorSlot.isEmpty()) continue;
            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(armorSlot);

            for (Map.Entry<String, RegistryObject<DamageTypeProtectionEnchantment>> entry
                    : ModEnchantments.PROTECTION_ENCHANTMENTS.entrySet()) {
                String type = entry.getKey();
                DamageTypeProtectionEnchantment ench = entry.getValue().orElse(null);
                if (ench == null) continue;
                int level = enchants.getOrDefault(ench, 0);
                if (level <= 0) continue;

                float resist = ench.getResistancePerLevel() * level;
                total.merge(type, resist, Float::sum);
            }
        }

        return total;
    }

    // ======================== 辅助方法 ========================
    private Map<String, Float> tryGetWeaponDistribution(ItemStack weapon) {
        if (weapon.isEmpty()) return null;
        ResourceLocation weaponId = BuiltInRegistries.ITEM.getKey(weapon.getItem());
        if (weaponId == null) return null;
        WeaponDistribution wd = ConfigLoader.getWeaponDistribution(weaponId);
        return (wd != null) ? wd.damageDistribution : null;
    }

    private Map<String, Float> getArmorResistance(LivingEntity entity) {
        Map<String, Float> total = new HashMap<>();

        for (ItemStack armorSlot : entity.getArmorSlots()) {
            if (armorSlot.isEmpty()) continue;
            ResourceLocation armorId = BuiltInRegistries.ITEM.getKey(armorSlot.getItem());
            if (armorId == null) continue;
            ArmorResistance ar = ConfigLoader.getArmorResistance(armorId);
            if (ar == null) continue;

            for (Map.Entry<String, Float> entry : ar.resistances.entrySet()) {
                // 护甲抗性乘算：多件护甲同类型抗性不再直接相加，
                // 而是各自独立减免后累乘（a=30%, b=20% → 1-(1-0.3)(1-0.2)=44% 而非 50%）
                total.merge(entry.getKey(), entry.getValue(),
                        (a, b) -> 100f * (1f - (1f - a / 100f) * (1f - b / 100f)));
            }
        }

        return total;
    }

    // ======================== Mixin 访问桥（绕过 javac 静态类型校验） ========================
    //
    // 背景：我们通过 @Mixin(Projectile.class) + @Unique 把 MixinProjectileDamageDistribution
    //       的 getter/setter 「注入」进 Projectile 类。运行时 Projectile 实例天然带这些方法，
    //       但 javac 编译时并不知道 Mixin 的存在（Mixin 在后续的 jar-in-jar / 类加载阶段才生效），
    //       所以直接写 `projectile instanceof MixinProjectileDamageDistribution accessor`
    //       会被 javac 报 "不兼容的类型：Projectile 无法转换为 MixinProjectileDamageDistribution"。
    //
    // 解决：以 Object 接收实例，再通过名字查找 getter/setter 方法并反射调用。
    //       方法名（damageoverhaul$getDamageDistributionOverride /
    //       damageoverhaul$setDamageDistributionOverride）完全由开发者定义的 Mixin 类决定，
    //       不依赖 MC 原生 SRG/MCP 映射，因此 dev / reobf 环境都不会失效。
    static final class ProjectileOverrideHelper {
        private static final String GETTER = "damageoverhaul$getDamageDistributionOverride";
        private static final String SETTER = "damageoverhaul$setDamageDistributionOverride";

        @SuppressWarnings("unchecked")
        static Map<String, Float> getOverride(Object projectile) {
            if (projectile == null) return null;
            try {
                java.lang.reflect.Method m = projectile.getClass().getMethod(GETTER);
                m.setAccessible(true);
                Object r = m.invoke(projectile);
                return (r instanceof Map) ? (Map<String, Float>) r : null;
            } catch (NoSuchMethodException e) {
                // Mixin 未加载（例如客户端还在加载期 / 旧版本 jar）→ 视为 override 不存在
                return null;
            } catch (Exception e) {
                LOGGER.warn("[ProjectileOverride] 读取 Mixin override 失败: {}", e.toString());
                return null;
            }
        }

        static boolean setOverride(Object projectile, Map<String, Float> override) {
            if (projectile == null) return false;
            try {
                java.lang.reflect.Method m = projectile.getClass()
                        .getMethod(SETTER, Map.class);
                m.setAccessible(true);
                m.invoke(projectile, override);
                return true;
            } catch (NoSuchMethodException e) {
                LOGGER.warn("[ProjectileOverride] Mixin 字段未注入（{} 缺失），射弹附魔覆盖未写入。实体={}",
                        SETTER, projectile.getClass().getName());
                return false;
            } catch (Exception e) {
                LOGGER.warn("[ProjectileOverride] 写入 Mixin override 失败: {}", e.toString());
                return false;
            }
        }
    }

    /**
     * 反射查找 LivingEntity.lastDamage 字段。
     * dev 环境名为 "lastDamage"，reobf 运行时被混淆为 SRG 名。
     */
    private static java.lang.reflect.Field findLastDamageField() {
        // 1) 尝试 dev 名
        try { return LivingEntity.class.getDeclaredField("lastDamage"); }
        catch (NoSuchFieldException ignored) {}
        // 2) 尝试常见 SRG 名
        for (String n : new String[]{"f_20896_", "f_20894_", "f_20895_"}) {
            try { return LivingEntity.class.getDeclaredField(n); }
            catch (NoSuchFieldException ignored) {}
        }
        // 3) 兜底：遍历所有 float 字段（排除 invulnerableTime、static）
        java.lang.reflect.Field best = null;
        for (java.lang.reflect.Field f : LivingEntity.class.getDeclaredFields()) {
            if (f.getType() == float.class
                    && !f.getName().equals("invulnerableTime")
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                best = f;
            }
        }
        return best;
    }

}

