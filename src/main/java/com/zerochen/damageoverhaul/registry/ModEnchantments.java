package com.zerochen.damageoverhaul.registry;

import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ModEnchantments {

    private static final Logger LOGGER = LoggerFactory.getLogger("DamageOverhaul|AnvilEnchant");

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, DamageOverhaulMod.MOD_ID);

    public static final Map<String, RegistryObject<DamageTypeProtectionEnchantment>> PROTECTION_ENCHANTMENTS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<DamageTypeStrikeEnchantment>> STRIKE_ENCHANTMENTS = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<DamageTypeProjectileStrikeEnchantment>> PROJECTILE_STRIKE_ENCHANTMENTS = new LinkedHashMap<>();

    private static List<String> INITIAL_DAMAGE_TYPES;

    public static List<String> getInitialDamageTypes() {
        if (INITIAL_DAMAGE_TYPES != null) return INITIAL_DAMAGE_TYPES;

        List<String> types = new ArrayList<>();
        try {
            Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(DamageOverhaulMod.MOD_ID);
            Path typesFile = configDir.resolve("damage_types.json");
            if (Files.exists(typesFile)) {
                try (InputStreamReader reader = new InputStreamReader(
                        Files.newInputStream(typesFile), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray arr = root.getAsJsonArray("damage_types");
                    if (arr != null) {
                        for (var e : arr) types.add(e.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (types.isEmpty()) {
            types.addAll(Arrays.asList("slashing", "blunt", "piercing", "magic",
                    "fire", "lightning", "dark", "holy"));
        }
        INITIAL_DAMAGE_TYPES = types;
        return types;
    }

    private static Integer INITIAL_MAX_ENCHANT_LEVEL;

    /**
     * 启动时早期预读 damage_types.json 的 max_enchant_level（附魔等级上限，默认255）。
     * 与 getInitialDamageTypes 同模式：附魔注册在注册表冻结前，此时 ConfigLoader 尚未加载，
     * 需直接从文件预读。解耦原版4级上限后，超高级附魔（铁砧叠加/指令）按线性公式正常生效。
     */
    public static int getMaxEnchantLevel() {
        if (INITIAL_MAX_ENCHANT_LEVEL != null) return INITIAL_MAX_ENCHANT_LEVEL;
        int result = 255;
        try {
            Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(DamageOverhaulMod.MOD_ID);
            Path typesFile = configDir.resolve("damage_types.json");
            if (Files.exists(typesFile)) {
                try (InputStreamReader reader = new InputStreamReader(
                        Files.newInputStream(typesFile), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("max_enchant_level")) {
                        result = Math.max(1, root.get("max_enchant_level").getAsInt());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        INITIAL_MAX_ENCHANT_LEVEL = result;
        return result;
    }

    public static void registerAll() {
        List<String> types = getInitialDamageTypes();
        EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };

        for (String type : types) {
            String protectKey = "protect_" + type;
            PROTECTION_ENCHANTMENTS.put(type, ENCHANTMENTS.register(protectKey,
                    () -> new DamageTypeProtectionEnchantment(type, ARMOR_SLOTS)));

            String strikeKey = "strike_" + type;
            STRIKE_ENCHANTMENTS.put(type, ENCHANTMENTS.register(strikeKey,
                    () -> new DamageTypeStrikeEnchantment(type)));

            String projStrikeKey = "projectile_strike_" + type;
            PROJECTILE_STRIKE_ENCHANTMENTS.put(type, ENCHANTMENTS.register(projStrikeKey,
                    () -> new DamageTypeProjectileStrikeEnchantment(type)));
        }
    }

    public static void registerBus(IEventBus bus) {
        ENCHANTMENTS.register(bus);
    }

    public static class DamageTypeProtectionEnchantment extends Enchantment {
        public final String damageType;

        public DamageTypeProtectionEnchantment(String damageType, EquipmentSlot[] slots) {
            super(Rarity.RARE, EnchantmentCategory.ARMOR, slots);
            this.damageType = damageType;
        }

        @Override public int getMaxLevel() { return getMaxEnchantLevel(); }
        @Override public int getMinCost(int level) { return 1 + (level - 1) * 6; }
        @Override public int getMaxCost(int level) { return this.getMinCost(level) + 15; }

        public float getResistancePerLevel() { return 5.0f; }
    }

    public static class DamageTypeStrikeEnchantment extends Enchantment {
        public final String damageType;

        public DamageTypeStrikeEnchantment(String damageType) {
            super(Rarity.RARE, EnchantmentCategory.WEAPON, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
            this.damageType = damageType;
        }

        @Override public int getMaxLevel() { return getMaxEnchantLevel(); }
        @Override public int getMinCost(int level) { return 1 + (level - 1) * 8; }
        @Override public int getMaxCost(int level) { return this.getMinCost(level) + 20; }

        public float getRatioPerLevel() { return 0.1f; }
    }

    /**
     * 射弹专用 Strike 附魔。
     * 仅可附魔于：纯射弹物品（对应实体为 Projectile 子类，且物品不具备武器性质）。
     * 效果：该射弹以"射弹分布"作为伤害来源时，对应伤害类型占比 +0.1/级（满级4级+0.4，不降低其他占比）。
     */
    public static class DamageTypeProjectileStrikeEnchantment extends Enchantment {
        public final String damageType;

        // 自定义射弹分类：仅用于提供默认canApply逻辑，真正的判定在canApplyAtEnchantingTable重写
        private static final EnchantmentCategory PROJ_CATEGORY = EnchantmentCategory.create(
                DamageOverhaulMod.MOD_ID + "_projectile",
                item -> false // 完全依赖下面重写的 canApplyAtEnchantingTable
        );

        public DamageTypeProjectileStrikeEnchantment(String damageType) {
            super(Rarity.RARE, PROJ_CATEGORY, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
            this.damageType = damageType;
        }

        @Override public int getMaxLevel() { return getMaxEnchantLevel(); }
        @Override public int getMinCost(int level) { return 1 + (level - 1) * 8; }
        @Override public int getMaxCost(int level) { return this.getMinCost(level) + 20; }

        public float getRatioPerLevel() { return 0.1f; }

        /**
         * 判定物品能否在附魔台上获得此附魔：
         * 条件1：该物品不是武器（与 weapon_distributions 的"默认武器判定"一致）
         * 条件2：该物品ID同名的实体存在且继承自 Projectile（即射出后为射弹实体）
         */
        @Override
        public boolean canApplyAtEnchantingTable(ItemStack stack) {
            return isPureProjectileItem(stack);
        }
    }

    /**
     * 公共静态判定：物品是否是"纯射弹物品"（可被 projectile_strike_<type> 附魔）。
     * 复用于：AnvilUpdateEvent（铁砧）、DamageHandler、附魔判定。
     * 判定规则：
     *   a) 非武器（非传统武器类且无 ATTACK_DAMAGE 正属性）
     *   b) 同 ID 的 ENTITY_TYPE 存在（Forge 1.20.1 的 getBaseClass() 对很多实体返回 Entity，不可靠）
     */
    public static boolean isPureProjectileItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return false;

        // a) 非武器
        boolean isTraditionalWeapon = item instanceof SwordItem || item instanceof AxeItem ||
                item instanceof BowItem || item instanceof CrossbowItem ||
                item instanceof TridentItem || item instanceof PickaxeItem ||
                item instanceof ShovelItem || item instanceof HoeItem;
        if (isTraditionalWeapon) {
            LOGGER.info("[isPureProjectileItem] {} → false (传统武器)", itemId);
            return false;
        }
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        float dmg = 0;
        for (AttributeModifier mod : mods.get(Attributes.ATTACK_DAMAGE)) dmg += (float) mod.getAmount();
        if (dmg > 0) {
            LOGGER.info("[isPureProjectileItem] {} → false (ATTACK_DAMAGE={} > 0)", itemId, dmg);
            return false;
        }

        // b) 同名实体类型存在
        // 注：Forge 1.20.1 的 EntityType.getBaseClass() 对 arrow 等实体返回 Entity（非 AbstractArrow），
        //     无法可靠判断是否为 Projectile 子类。条件 a) 已排除所有武器，此处只需确认同名实体存在。
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(itemId)) {
            LOGGER.info("[isPureProjectileItem] {} → false (ENTITY_TYPE 无此 ID)", itemId);
            return false;
        }
        return true;
    }

    /**
     * 查询某 ItemStack 上的所有 projectile_strike_<type> 附魔等级之和（用于快速日志）。
     */
    public static int countProjectileStrikeEnchantments(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int c = 0;
        Map<Enchantment, Integer> em = EnchantmentHelper.getEnchantments(stack);
        for (var entry : PROJECTILE_STRIKE_ENCHANTMENTS.entrySet()) {
            Enchantment enc = entry.getValue().get();
            c += em.getOrDefault(enc, 0);
        }
        return c;
    }

    // =============== Forge EventBus Subscriber（AnvilUpdateEvent 等 FORGE 总线上的事件） ===============
    @Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ForgeBusHandlers {

        /**
         * 铁砧合并的核心补救：原版对于 ArrowItem 这类 isEnchantable()=false 的物品，
         * 即使拿着 projectile_strike 附魔书在铁砧上合并也无法通过（AnvilMenu 里需要
         * enchantment.canApplyAtEnchantingTable(left) && left.isEnchantable() 才能写入）。
         * 我们在 AnvilUpdateEvent 里手动处理：当左输入是纯射弹物品、右输入是含任意
         * projectile_strike 附魔的附魔书时，直接生成输出物品、计算 cost，绕过原版限制。
         */
        @SubscribeEvent
        public static void onAnvilUpdate(AnvilUpdateEvent event) {
            ItemStack left = event.getLeft();
            ItemStack right = event.getRight();
            if (left == null || left.isEmpty() || right == null || right.isEmpty()) return;

            // 左：纯射弹物品（可附 projectile_strike）
            if (!isPureProjectileItem(left)) return;
            // 右：必须是附魔书
            if (!(right.getItem() instanceof EnchantedBookItem)) return;

            // 从附魔书中取附魔列表
            Map<Enchantment, Integer> bookEnchants = EnchantmentHelper.getEnchantments(right);
            if (bookEnchants == null || bookEnchants.isEmpty()) return;

            // 只取出 projectile_strike 相关附魔（以及其他我们允许的，比如保护？不行，保护是防具的）
            Map<Enchantment, Integer> applicable = new LinkedHashMap<>();
            for (var e : bookEnchants.entrySet()) {
                Enchantment enc = e.getKey();
                int level = e.getValue();
                // 允许：所有 projectile_strike_<type>（我们自己判定）
                if (enc instanceof DamageTypeProjectileStrikeEnchantment) {
                    applicable.put(enc, level);
                }
                // 通用兼容：如果附魔自己的 canApplyAtEnchantingTable(left) 返回 true（比如其他模组加的射弹相关附魔）也允许
                else if (enc.canApplyAtEnchantingTable(left)) {
                    applicable.put(enc, level);
                }
            }
            if (applicable.isEmpty()) return; // 这本书没有任何可合并的射弹附魔，跳过交给原版

            // 构造输出物品：左物品的副本
            ItemStack output = left.copy();
            Map<Enchantment, Integer> currentEnchants = new LinkedHashMap<>(EnchantmentHelper.getEnchantments(output));
            int cost = 0;

            // 合并 applicable（取 max，同原版铁砧合并策略）
            for (var newEntry : applicable.entrySet()) {
                Enchantment enc = newEntry.getKey();
                int newLvl = newEntry.getValue();
                int curLvl = currentEnchants.getOrDefault(enc, 0);
                int merged = (newLvl == curLvl) ? Math.min(newLvl + 1, enc.getMaxLevel()) : Math.max(newLvl, curLvl);
                currentEnchants.put(enc, merged);
                // 计算经验消耗（参考原版铁砧：稀有度 RARE 每级 4）
                int rarityMultiplier = switch (enc.getRarity()) {
                    case COMMON -> 1; case UNCOMMON -> 2; case RARE -> 4; case VERY_RARE -> 8;
                };
                cost += Math.abs(merged) * rarityMultiplier;
            }

            // 处理命名（铁砧重命名）
            String newName = event.getName();
            if (newName != null && !newName.isBlank() && !newName.equals(output.getHoverName().getString())) {
                output.setHoverName(net.minecraft.network.chat.Component.literal(newName));
                cost += 1; // 原版命名成本
            }
            cost = Math.max(1, cost); // 最少收1级经验

            EnchantmentHelper.setEnchantments(currentEnchants, output);
            int projCount = countProjectileStrikeEnchantments(output);
            LOGGER.info("[Anvil] 合并射弹附魔: 左={}, 附魔书投射附魔数={}, 输出射弹附魔总等级={}, 经验消耗={}lv",
                    left.getItem(), countProjectileStrikeEnchantments(right), projCount, cost);

            event.setOutput(output);
            event.setCost(cost);
            event.setMaterialCost(1); // 消耗 1 本附魔书（右侧）
        }
    }

    // =============== MOD EventBus Subscriber（创造模式物品栏过滤等 MOD 总线事件） ===============
    @Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusHandlers {

        /**
         * 创造模式物品栏中，本模组附魔书只展示 1~4 级。
         * 更高等级的附魔书从创造标签页中移除，但 getMaxLevel() 仍返回配置上限（默认255），
         * 因此铁砧叠加、/give 指令赋予的高等级附魔仍按线性公式正常生效。
         * 仅过滤本模组（damageoverhaul）命名空间下的附魔，不影响原版/其他模组。
         */
        @SubscribeEvent
        public static void onCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
            // Forge 1.20.1：getEntries() 返回 MutableHashedLinkedMap<ItemStack, TabVisibility>，
            // 该类实现 Iterable<Map.Entry<K,V>>，用 iterator() 遍历并 remove()。
            var entries = event.getEntries();
            Iterator<Map.Entry<ItemStack, CreativeModeTab.TabVisibility>> it = entries.iterator();
            while (it.hasNext()) {
                ItemStack stack = it.next().getKey();
                if (!(stack.getItem() instanceof EnchantedBookItem)) continue;
                Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
                boolean hide = false;
                for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                    if (e.getValue() != null && e.getValue() > 4 && isModEnchantment(e.getKey())) {
                        hide = true;
                        break;
                    }
                }
                if (hide) it.remove();
            }
        }

        private static boolean isModEnchantment(Enchantment enc) {
            ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(enc);
            return id != null && DamageOverhaulMod.MOD_ID.equals(id.getNamespace());
        }
    }
}
