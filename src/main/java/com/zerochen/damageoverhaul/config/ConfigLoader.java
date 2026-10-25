package com.zerochen.damageoverhaul.config;

import com.google.common.collect.Multimap;
import com.google.gson.*;
import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.registry.ModEnchantments;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.*;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigLoader {

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve(DamageOverhaulMod.MOD_ID);
    private static final Path DAMAGE_TYPES_FILE = CONFIG_PATH.resolve("damage_types.json");
    // 文件夹化配置：以下 5 个大文件已改为文件夹结构（_meta.json + per-modid.json）
    private static final Path ENTITY_RESISTANCES_DIR = CONFIG_PATH.resolve("entity_resistances");
    private static final Path WEAPON_DISTRIBUTIONS_DIR = CONFIG_PATH.resolve("weapon_distributions");
    private static final Path RANGED_CONFIG_DIR = CONFIG_PATH.resolve("ranged_config");
    // 旧单文件路径（仅用于迁移检测：旧文件存在且文件夹为空时自动拆分）
    private static final Path ENTITY_RESISTANCES_LEGACY = CONFIG_PATH.resolve("entity_resistances.json");
    private static final Path WEAPON_DISTRIBUTIONS_LEGACY = CONFIG_PATH.resolve("weapon_distributions.json");
    private static final Path RANGED_CONFIG_LEGACY = CONFIG_PATH.resolve("ranged_config.json");
    private static final Path BLOCK_DIST_FILE = CONFIG_PATH.resolve("block_distributions.json");
    private static final Path ARMOR_FILE = CONFIG_PATH.resolve("armor_resistances.json");
    private static final Path UNARMED_FILE = CONFIG_PATH.resolve("unarmed_distributions.json");
    private static final Path MAPPINGS_FILE = CONFIG_PATH.resolve("damage_type_mappings.json");
    private static final Path MECHANICS_FILE = CONFIG_PATH.resolve("special_mechanics.json");
    private static final Path MANA_REPAIR_FILE = CONFIG_PATH.resolve("mana_repair_config.json");
    /** 射弹附魔（projectile_strike_<damage_type>）的加成配置文件：含各等级的加成比例（每级占比增量） */
    private static final Path PROJECTILE_STRIKE_FILE = CONFIG_PATH.resolve("projectile_strike_config.json");
    private static final Path DISPLAY_CONFIG_FILE = CONFIG_PATH.resolve("display_config.json");
    private static final Path PROJ_ITEM_MAP_FILE = CONFIG_PATH.resolve("projectile_item_mappings.json");
    private static final Path CHARMS_FILE = CONFIG_PATH.resolve("charms.json");
    private static final Path EXTERNAL_CHARMS_FILE = CONFIG_PATH.resolve("external_charms.json");
    private static final Path MOB_EFFECTS_FILE = CONFIG_PATH.resolve("mob_effects_config.json");
    /** 武器补正倍率表（按 more_attributes 基础能力点缩放武器伤害；玩家手动填写）— 文件夹化 */
    private static final Path WEAPON_SCALING_DIR = CONFIG_PATH.resolve("weapon_scaling");
    private static final Path WEAPON_SCALING_LEGACY = CONFIG_PATH.resolve("weapon_scaling.json");
    /** 盔甲补正倍率表（按 more_attributes 基础能力点缩放盔甲减伤；逐件连乘）— 文件夹化 */
    private static final Path ARMOR_SCALING_DIR = CONFIG_PATH.resolve("armor_scaling");
    private static final Path ARMOR_SCALING_LEGACY = CONFIG_PATH.resolve("armor_scaling.json");
    /** 内部属性系统配置（more_attributes 未安装时启用：9项能力的baseLevel/上限/升级曲线/行为经验来源） */
    private static final Path INTERNAL_ATTRIBUTES_FILE = CONFIG_PATH.resolve("internal_attributes.json");

    // ========== 冲击力/韧性系统配置文件 ==========
    /** 武器削韧值 + 关联冲击效果ID（独立文件，每武器一条） */
    private static final Path WEAPON_GUARD_BREAK_FILE = CONFIG_PATH.resolve("weapon_guard_break.json");
    /** 护甲韧性与抗冲击（独立文件，每件护甲一条） */
    private static final Path ARMOR_TOUGHNESS_FILE = CONFIG_PATH.resolve("armor_toughness.json");
    /** 玩家基础韧性与抗冲击（单条配置） */
    private static final Path PLAYER_CONFIG_FILE = CONFIG_PATH.resolve("player_config.json");
    /** 冲击效果预设表（direction/distance/duration_tick/ai_pause_tick） */
    private static final Path IMPACT_EFFECTS_FILE = CONFIG_PATH.resolve("impact_effects.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<String> DEFAULT_DAMAGE_TYPES = Arrays.asList(
            "slashing", "blunt", "piercing", "magic", "fire", "lightning", "dark", "holy");

    private static List<String> damageTypes = new ArrayList<>();
    /** 附魔等级上限（damage_types.json: max_enchant_level，默认255；解耦原版4级上限，允许超高级附魔按线性公式生效） */
    private static int maxEnchantLevel = 255;
    private static final int DEFAULT_MAX_ENCHANT_LEVEL = 255;
    private static final Map<ResourceLocation, EntityResistance> RESISTANCE_CACHE = new HashMap<>();
    private static final Map<ResourceLocation, WeaponDistribution> WEAPON_CACHE = new HashMap<>();
    private static final Map<ResourceLocation, ArmorResistance> ARMOR_CACHE = new HashMap<>();
    private static final Map<ResourceLocation, WeaponDistribution> UNARMED_CACHE = new HashMap<>();
    // 伤害类型映射：键格式 "modid:path"（具体伤害类型）或 "#modid:tagpath"（标签）
    // 值为混合分布 Map<String, Float>（伤害类型 → 绝对占比），支持一种伤害源映射到多种伤害类型
    private static final Map<String, Map<String, Float>> DAMAGE_TYPE_MAPPINGS = new LinkedHashMap<>();

    // 自动扫描推断：为注册表中所有未映射的伤害类型按标签规则推断映射（含模组新增）
    private static boolean autoInferEnabled = true;
    // 推断规则：键 "#modid:tagpath"（标签）或 "*"（无标签匹配时的兜底），值 = 本模组伤害类型名
    private static final Map<String, String> AUTO_INFER_RULES = new LinkedHashMap<>();
    // 远程武器配置
    private static String rangedDamageSource = "projectile"; // "weapon" 或 "projectile"（默认射弹决定）
    private static final Map<ResourceLocation, ProjectileConfig> PROJECTILE_CACHE = new HashMap<>();

    // 自动学习：远程伤害结算时若来源武器无分布配置，自动添加默认条目（默认穿刺100%）
    private static boolean autoLearnRangedWeapons = true;

    // 方块源性伤害分布：键 = 方块ID（如 minecraft:magma_block / minecraft:campfire）
    private static final Map<ResourceLocation, BlockDistEntry> BLOCK_DIST_CACHE = new LinkedHashMap<>();
    // 自动学习：方块源性伤害结算时为无条目的方块自动生成 use_default=true 条目（避免硬编码默认方块表）
    private static boolean autoLearnBlockDamage = true;

    // 射弹附魔加成配置：键 = 等级字符串 "1"/"2"/...，值 = 对应伤害类型占比增量
    // （完全由 projectile_strike_config.json 驱动，无任何硬编码）
    private static final Map<String, Float> PROJECTILE_STRIKE_BONUS_LEVELS = new LinkedHashMap<>();

    /** 射弹配置：伤害分布 + 是否遵循默认伤害来源 */
    public static class ProjectileConfig {
        public boolean followDefault;
        public Map<String, Float> damageDistribution;
        public ProjectileConfig(boolean followDefault, Map<String, Float> damageDistribution) {
            this.followDefault = followDefault;
            this.damageDistribution = damageDistribution;
        }
    }

    /**
     * 判断某射弹实体ID是否为"同时作为武器和射弹存在"的两用场景（应 follow_default=false，走武器来源）。
     *
     * 覆盖两种场景：
     *
     * 场景A：原版1对1（物品id = 实体id，且该物品是武器）
     *   例：minecraft:trident（三叉戟物品id = 三叉戟射弹id，WEAPON_CACHE有同名条目）
     *
     * 场景B：模组多对一（多个武器物品共用同一个射弹实体id，典型如斯巴达武器 javelin）
     *   例：spartanweaponry:iron_javelin / diamond_javelin 两个武器物品 → 都发射 spartanweaponry:javelin 实体
     *   判定思路：
     *     1) 该射弹实体类是"可投掷型武器发射的弹道"（AbstractArrow 子类 或 ThrowableItemProjectile 子类），
     *        这样天然排除了恶魂火球/凋灵头颅/潜影贝子弹/羊驼唾沫等纯射弹。
     *     2) 注册表中不存在"id = 射弹实体id"的物品注册（即找不到1:1对应物品），
     *        这样天然排除了 arrow/snowball/egg/ender_pearl/potion/experience_bottle 等原版纯射弹
     *        （它们都有与实体同名的物品，且不是武器）。
     *   同时满足1和2 → 这是模组多对一投射物实体，对应至少有一个投掷武器物品可发射它 → 判定为两用。
     *
     * @param projectileId 射弹实体的 ResourceLocation（注册表名）
     * @return true 表示两用场景，该射弹应 follow_default=false（拒绝默认=武器来源）
     */
    private static boolean isWeaponProjectileDualUse(ResourceLocation projectileId) {
        // 场景A：原版1对1（trident等）——实体id=武器物品id
        if (WEAPON_CACHE.containsKey(projectileId)) return true;

        // 场景B：模组多对一（spartan javelin等）
        EntityType<?> etype = BuiltInRegistries.ENTITY_TYPE.get(projectileId);
        if (etype == null) return false;
        Class<? extends Entity> eclass = etype.getBaseClass();

        // B-1：实体类必须属于"可投掷武器发射出的弹道类型"
        // AbstractArrow（箭/三叉戟/标枪类弹道）、ThrowableItemProjectile（雪球类可投掷弹道）
        boolean isThrowableProjectileType =
                AbstractArrow.class.isAssignableFrom(eclass)
                        || ThrowableItemProjectile.class.isAssignableFrom(eclass);
        if (!isThrowableProjectileType) return false; // 恶魂火球/潜影贝子弹/羊驼唾沫等纯射弹→不认为是武器场景

        // B-2：不存在与射弹id完全同名的Item（即不是1:1对应物品的原版射弹）
        Item sameIdItem = BuiltInRegistries.ITEM.get(projectileId);
        // Forge 的 DefaultedRegistry 对无效键返回默认值（Items.AIR），所以存在同ID物品=不是AIR
        boolean hasSameIdItem = (sameIdItem != null && sameIdItem != Items.AIR
                && BuiltInRegistries.ITEM.getKey(sameIdItem) != null
                && BuiltInRegistries.ITEM.getKey(sameIdItem).equals(projectileId));

        if (!hasSameIdItem) {
            // AbstractArrow/ThrowableItemProjectile 子类 + 无同ID物品 = 模组共用投射物实体
            // 例：spartanweaponry:javelin / apotheosis:explosive_arrow 等
            // → 判定为两用场景：实际使用时由玩家手持的投掷武器物品（iron_javelin等）提供分布
            return true;
        }

        // 存在同ID物品（如 arrow、spectral_arrow、snowball、egg 等），走场景A的 WEAPON_CACHE 判定（上面已经判过，不在其中）→ 不是两用
        return false;
    }

    /**
     * 计算射弹配置的默认 follow_default 值：
     * - 两用物品（武器+射弹）= false（拒绝全局默认 projectile → 实际用 weapon，由武器决定伤害类型）
     * - 纯射弹物品 = true（遵循全局默认 projectile → 实际用射弹分布）
     */
    private static boolean defaultFollowDefaultFor(ResourceLocation projectileId) {
        return !isWeaponProjectileDualUse(projectileId);
    }
    // 弹开机制配置
    private static boolean deflectionEnabled = true;
    private static float deflectionX = 1.0f / 6.0f;   // X 默认 1/6
    private static float deflectionZ = 0.1f;           // Z 默认 10%
    private static final List<String> deflectionAffectedTypes = new ArrayList<>();

    // 热路径调试日志开关（special_mechanics.json: enable_debug_logging，默认关闭）
    // 开启后 DamageHandler 的 [ProjectileVariant]/[Ranged]/[DamageCalc]/[Enchant]/[BlockDist] 等逐事件日志才会输出
    private static boolean debugLoggingEnabled = false;

    // 魔力修复配置
    private static boolean manaRepairEnabled = false;
    private static int defaultManaPerDurability = 60;
    private static boolean staticRepairEnabled = true; // 静态修复：背包中自动吸收魔力
    private static int staticRepairInterval = 40;      // 静态修复间隔（tick），默认 2 秒
    private static int staticRepairPerTick = 1;        // 每次静态修复回复的耐久点数
    private static final Map<ResourceLocation, ManaRepairEntry> MANA_REPAIR_CACHE = new HashMap<>();

    // ========== 饰品配置（charms.json） ==========
    // 通用饰品框架：每个饰品绑定一种伤害类型，含攻击端增伤 + 防御端抗性 + 多帧流光贴图
    // 叠加规则：同类（同 damage_type）多个饰品取最高值，不线性叠加
    private static final Map<String, CharmConfig> CHARM_CACHE = new LinkedHashMap<>();
    private static int charmDefaultFrameSpeed = 8;
    /** 有内置独立手绘贴图的伤害类型（已烘焙进 JAR，对应 assets/damageoverhaul/textures/item/charm/<type>_charm_0.png）。
     *  这些类型的默认饰品引用独立贴图 + 静态单帧（显示手绘原色）。
     *  其余类型（物理 slashing/blunt/piercing 等）默认用共享灰白 charm_base_0（显示原色，暂搁置等手绘）。 */
    private static final Set<String> CHARM_WITH_TEXTURE = new LinkedHashSet<>(Arrays.asList(
            "fire", "magic", "lightning", "holy", "dark"));

    public static class CharmConfig {
        public final String id;                 // 饰品 ID（物品注册名）
        public final String displayName;        // 显示名（可被 lang 覆盖）
        public final String damageType;         // 关联伤害类型
        public final float attackDamageBonus;   // 攻击端 +X（如 0.15 = +15%）
        public final float defenseResistance;    // 防御端抗性 +X（如 0.10 = +10%）
        public final float attackDamageFlat;    // 固定攻击增伤（数值）
        public final float defenseResistanceFlat; // 固定防御抗性（数值）
        public final List<String> textureFrames;   // 贴图帧名列表
        public final List<Integer> frameOrder;     // 帧播放顺序（0→1→2→3 等）
        public final int frameSpeed;               // 每 N tick 切换一帧
        public final String tint;                   // 染色 hex（如 "FF3030"），留空跳过染色
        public final float toughness;

        public CharmConfig(String id, String displayName, String damageType,
                           float attackDamageBonus, float defenseResistance,
                           float attackDamageFlat, float defenseResistanceFlat,
                           List<String> textureFrames, List<Integer> frameOrder,
                           int frameSpeed, String tint, float toughness) {
            this.id = id;
            this.displayName = displayName;
            this.damageType = damageType;
            this.attackDamageBonus = attackDamageBonus;
            this.defenseResistance = defenseResistance;
            this.attackDamageFlat = attackDamageFlat;
            this.defenseResistanceFlat = defenseResistanceFlat;
            this.textureFrames = textureFrames;
            this.frameOrder = frameOrder;
            this.frameSpeed = frameSpeed;
            this.tint = tint;
            this.toughness = toughness;
        }
    }

    // ========== 第三方饰品配置（external_charms.json） ==========
    // 按物品ID精确匹配其他模组的 Curios 饰品，为其提供混合分布的伤害增幅与类型抗性。
    // 叠加规则：与自家饰品一致，同伤害类型取最高不叠加（自家+第三方混戴时合并取最高）。
    private static final Map<String, ExternalCharmConfig> EXTERNAL_CHARM_CACHE = new LinkedHashMap<>();
    private static boolean externalCharmsEnabled = true;
    /** 启动时是否全量扫描注册表中带 curios:* 标签的第三方饰品并自动收录（全0行） */
    private static boolean externalCharmsAutoScan = true;

    /** 第三方饰品配置：一件物品可同时提供多种伤害类型的增伤/抗性（混合分布） */
    public static class ExternalCharmConfig {
        public final String itemId;                          // 物品ID（modid:path）
        public final Map<String, Float> attackBonuses;       // type → 伤害增幅比例（如 0.15 = +15%）
        public final Map<String, Float> defenseResistances;  // type → 类型抗性比例（如 0.10 = 减免10%）
        public float toughness;

        public ExternalCharmConfig(String itemId, Map<String, Float> attackBonuses, Map<String, Float> defenseResistances,
                                   float toughness) {
            this.itemId = itemId;
            this.attackBonuses = attackBonuses == null ? new LinkedHashMap<>() : attackBonuses;
            this.defenseResistances = defenseResistances == null ? new LinkedHashMap<>() : defenseResistances;
            this.toughness = toughness;
        }
    }

    // ========== 药水效果配置（mob_effects_config.json） ==========
    // 每种伤害类型 × 4 个效果（resist_/vulnerable_/amplify_/weaken_<type>，由 ModMobEffects 注册）。
    // 每类型一行 4 个每级数值；新伤害类型自动按 defaults 行补齐。数值热重载生效。
    private static final Map<String, MobEffectValues> MOB_EFFECT_CACHE = new LinkedHashMap<>();
    private static boolean mobEffectsEnabled = true;
    /** 新增伤害类型补行时使用的默认每级数值（defaults 行可配置） */
    private static final MobEffectValues DEFAULT_MOB_EFFECT_VALUES = new MobEffectValues(5.0f, 5.0f, 0.10f, 0.10f);
    /** 运行时从 defaults 行读到的默认值（加载后替换，供补行用） */
    private static MobEffectValues mobEffectDefaults = DEFAULT_MOB_EFFECT_VALUES;

    /** 单个伤害类型的 4 个每级效果数值 */
    public static class MobEffectValues {
        public final float resistUpPerLevel;    // 抗性提升：每级 +X% 该类型抗性（加性并入附魔抗性池，5.0 = 5%）
        public final float resistDownPerLevel;  // 抗性降低：每级 -X% 该类型抗性
        public final float damageUpPerLevel;    // 伤害增幅：每级 +X 该类型伤害（乘区小数，0.10 = +10%）
        public final float damageDownPerLevel;  // 伤害削弱：每级 -X 该类型伤害

        public MobEffectValues(float resistUpPerLevel, float resistDownPerLevel,
                               float damageUpPerLevel, float damageDownPerLevel) {
            this.resistUpPerLevel = resistUpPerLevel;
            this.resistDownPerLevel = resistDownPerLevel;
            this.damageUpPerLevel = damageUpPerLevel;
            this.damageDownPerLevel = damageDownPerLevel;
        }
    }

    // ========== 装备补正（weapon_scaling/ armor_scaling/ 文件夹） ==========
    // 软依赖 more_attributes（org.mantodea.more_attributes）的玩家属性点：
    // 每件装备按「伤害类型 × 基础能力」给出倍率，补正 = Σ(倍率 × 有效属性点)。
    // 有效属性点 = 玩家已投等级 − 该能力 baseLevel（可为负，与 more_attributes 自身缩放语义一致）。
    //   武器：补正并入攻击加性乘区（饰品+药水+武器补正），按伤害类型独立；
    //   盔甲：每件每个类型独立补正，4 件逐件连乘 ∏(1−补正)，夹在附魔+药水抗性之后、饰品抗性之前。
    // 启动全量扫描武器/护甲生成全0行（纯收录不显示）；同步给客户端时仅传非0条目。
    private static final Map<String, ScalingRow> WEAPON_SCALING_CACHE = new LinkedHashMap<>();
    private static final Map<String, ScalingRow> ARMOR_SCALING_CACHE = new LinkedHashMap<>();
    private static boolean weaponScalingEnabled = true;
    private static boolean armorScalingEnabled = true;
    /** 基础能力列名（来自 more_attributes 数据包；回退为内置9能力） */
    private static List<String> scalingAttributes = new ArrayList<>();
    /** 字母等级阈值（有序，降序）：补正比例≥阈值 → 对应字母；补正≤0 不显示 */
    private static final LinkedHashMap<String, Float> DEFAULT_GRADE_THRESHOLDS = new LinkedHashMap<>();
    static {
        DEFAULT_GRADE_THRESHOLDS.put("S", 1.5f);
        DEFAULT_GRADE_THRESHOLDS.put("A", 1.0f);
        DEFAULT_GRADE_THRESHOLDS.put("B", 0.75f);
        DEFAULT_GRADE_THRESHOLDS.put("C", 0.5f);
        DEFAULT_GRADE_THRESHOLDS.put("D", 0.25f);
        DEFAULT_GRADE_THRESHOLDS.put("E", 0.0f);
    }
    private static LinkedHashMap<String, Float> weaponGradeThresholds = new LinkedHashMap<>(DEFAULT_GRADE_THRESHOLDS);
    private static LinkedHashMap<String, Float> armorGradeThresholds = new LinkedHashMap<>(DEFAULT_GRADE_THRESHOLDS);

    /**
     * 装备固有补正的字母等级阈值（按能力）：把一件装备各伤害类型下该能力的倍率求和
     * （如 0.03 = 每点有效属性 +3%），≥阈值 → 对应字母；倍率≤0 的能力不显示。
     * 黑魂式「力量：S」评级，与持有者属性点无关，纯看装备本身的补正潜力。
     */
    private static final LinkedHashMap<String, Float> DEFAULT_ABILITY_GRADE_THRESHOLDS = new LinkedHashMap<>();
    static {
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("S", 0.05f);
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("A", 0.04f);
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("B", 0.03f);
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("C", 0.02f);
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("D", 0.01f);
        DEFAULT_ABILITY_GRADE_THRESHOLDS.put("E", 0.0f);
    }
    private static LinkedHashMap<String, Float> weaponAbilityGradeThresholds = new LinkedHashMap<>(DEFAULT_ABILITY_GRADE_THRESHOLDS);
    private static LinkedHashMap<String, Float> armorAbilityGradeThresholds = new LinkedHashMap<>(DEFAULT_ABILITY_GRADE_THRESHOLDS);

    // ========== 冲击力/韧性系统缓存 ==========
    // 武器削韧值 + 关联冲击效果ID：itemId → WeaponGuardBreak
    private static final Map<String, WeaponGuardBreak> WEAPON_GUARD_BREAK_CACHE = new LinkedHashMap<>();
    // 护甲韧性与抗冲击：itemId → ArmorToughness
    private static final Map<String, ArmorToughness> ARMOR_TOUGHNESS_CACHE = new LinkedHashMap<>();
    // 玩家基础韧性与抗冲击（单例，load 后替换）
    private static float playerBaseToughness = 10.0f;
    // 玩家基础抗冲击：免疫的冲击效果 ID 列表 + 冲击效果重映射 Map
    private static final List<String> playerImmuneImpactEffects = new ArrayList<>();
    private static final Map<String, String> playerImpactEffectRemap = new LinkedHashMap<>();
    // 冲击效果预设：effectId → ImpactEffect
    private static final Map<String, ImpactEffect> IMPACT_EFFECTS_CACHE = new LinkedHashMap<>();

    /**
     * 单件装备的补正倍率表：伤害类型 → 基础能力 → 倍率（如 0.02 = 每点有效属性 +2%）。
     * 稠密存储（加载时按 damageTypes × scalingAttributes 补0），文件中写完整表格。
     */
    public static class ScalingRow {
        public final String itemId;
        public final Map<String, Map<String, Float>> multipliers; // type → attr → multiplier

        public ScalingRow(String itemId, Map<String, Map<String, Float>> multipliers) {
            this.itemId = itemId;
            this.multipliers = multipliers == null ? new LinkedHashMap<>() : multipliers;
        }

        public boolean isAllZero() {
            for (Map<String, Float> attrs : multipliers.values()) {
                for (Float v : attrs.values()) {
                    if (v != null && v != 0f) return false;
                }
            }
            return true;
        }
    }

    // ========== 内部属性系统（internal_attributes.json） ==========
    // more_attributes 未安装时启用的内置9能力：strength(baseLevel=10)/health/focus/stamina/
    // endurance/intelligence/agility/skill/luck。玩家通过行为自动获得经验并升级（幂次曲线），
    // 有效属性点 = 等级 − baseLevel，供装备补正系统作为数据源。
    private static boolean internalAttributesEnabled = true;
    /** 能力元数据：name → [baseLevel, maxLevel]（有序，文件可增删能力/调整数值） */
    private static final LinkedHashMap<String, int[]> INTERNAL_ATTRIBUTES = new LinkedHashMap<>();
    /** 升级曲线：升到下一级所需经验 = base_xp × (当前有效等级+1)^exponent */
    private static double internalLevelBaseXp = 100.0;
    private static double internalLevelExponent = 1.5;
    /** 击杀经验按目标最大生命值放大：经验 × max(1, 目标最大生命 ÷ 此除数) */
    private static double killHealthDivisor = 20.0;
    /** 行为经验来源：来源键 → 能力 → 每次获得经验（如 kill → strength → 5.0） */
    private static final Map<String, Map<String, Double>> INTERNAL_XP_SOURCES = new LinkedHashMap<>();

    // ========== 显示开关（display_config.json） ==========
    private static final Map<String, Boolean> DISPLAY_CONFIG = new LinkedHashMap<>();
    static {
        // 所有显示开关的默认值（全部开启），键名即为 display_config.json 中的配置项
        DISPLAY_CONFIG.put("show_weapon_distribution", true);
        DISPLAY_CONFIG.put("show_armor_resistance", true);
        DISPLAY_CONFIG.put("show_projectile_distribution", true);
        DISPLAY_CONFIG.put("show_mana_repair", true);
        DISPLAY_CONFIG.put("show_external_charm_bonus", true);
        DISPLAY_CONFIG.put("show_equipment_scaling", true);
        DISPLAY_CONFIG.put("show_weapon_guard_break", true);
        DISPLAY_CONFIG.put("show_armor_toughness", true);
    }

    // ========== 射弹物品映射（projectile_item_mappings.json） ==========
    // 键 = 物品ID，值 = ProjectileItemMapping（实体ID + replace_entity标记）
    private static final Map<ResourceLocation, ProjectileItemMapping> PROJ_ITEM_MAP = new LinkedHashMap<>();
    private static boolean allowDangerousReplace = false;

    /** 射弹物品映射数据类 */
    public static class ProjectileItemMapping {
        public ResourceLocation entityId;
        public boolean replaceEntity;
        public ProjectileItemMapping(ResourceLocation entityId, boolean replaceEntity) {
            this.entityId = entityId;
            this.replaceEntity = replaceEntity;
        }
    }

    // 服务器 Level 引用，用于安全创建实体实例以检测射弹类型
    private static net.minecraft.world.level.Level scanLevel = null;

    // 本次会话中创建失败的实体类型 ID（如模组版本不兼容导致 NoClassDefFoundError），
    // 跳过重试以避免重复副作用与日志刷屏；下次启动会重新尝试
    private static final java.util.Set<ResourceLocation> FAILED_SCAN_IDS = new HashSet<>();

    public static class ManaRepairEntry {
        public boolean enabled;
        public int manaPerDurability;
        public ManaRepairEntry(boolean enabled, int manaPerDurability) {
            this.enabled = enabled;
            this.manaPerDurability = manaPerDurability;
        }
    }

    // ======================== 配置迁移基础设施 ========================
    // 当前配置架构版本。每次修改配置文件结构时递增。
    // 旧配置文件（无 _schema_version 或版本更低）会被自动迁移到当前版本。
    // v4: 移除 impact_resistance / base_impact_resistance 数值字段，改为免疫列表 + 重映射 Map；
    //     韧性系统从静态改为动态（currentToughness），新增 weapon_guard_break / armor_toughness /
    //     player_config / impact_effects 四个配置文件；charms / external_charms / entity_resistances
    //     新增 toughness / immune_impact_effects / impact_effect_remap 字段。
    public static final int CONFIG_SCHEMA_VERSION = 4;

    // 迁移日志：记录本次加载周期内所有自动修改
    private static final List<String> MIGRATION_LOG = new ArrayList<>();
    private static String migrationTimestamp = "";
    private static final Path BACKUP_DIR = CONFIG_PATH.resolve("backups");

    /** 重置迁移日志（每次 loadAllConfigs 开始时调用） */
    private static void resetMigrationLog() {
        MIGRATION_LOG.clear();
        migrationTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
    }

    /** 记录一条迁移日志 */
    private static void logMigration(String message) {
        MIGRATION_LOG.add(message);
        DamageOverhaulMod.LOGGER.info("[ConfigMigration] {}", message);
    }

    /** 获取迁移日志摘要（用于命令输出） */
    public static List<String> getMigrationLog() {
        return new ArrayList<>(MIGRATION_LOG);
    }

    /** 判断本次加载是否发生了迁移 */
    public static boolean hasMigrationOccurred() {
        return !MIGRATION_LOG.isEmpty();
    }

    /**
     * 在覆盖配置文件前备份原文件。
     * 仅当文件已存在时才备份，备份到 backups/<timestamp>/ 目录下。
     */
    private static void backupBeforeSave(Path configFile, String reason) {
        try {
            if (!Files.exists(configFile)) return;
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            Path sessionDir = BACKUP_DIR.resolve(migrationTimestamp);
            if (!Files.exists(sessionDir)) Files.createDirectories(sessionDir);
            Path backup = sessionDir.resolve(configFile.getFileName().toString());
            Files.copy(configFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DamageOverhaulMod.LOGGER.info("[ConfigMigration] Backed up {} (reason: {})",
                    configFile.getFileName(), reason);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.warn("[ConfigMigration] Failed to backup {}: {}",
                    configFile.getFileName(), e.getMessage());
        }
    }

    /**
     * 检查 JSON 对象中是否存在指定字段，若缺失则记录迁移日志。
     * @return true 如果字段缺失（调用方应据此设置 needUpdate）
     */
    private static boolean checkFieldExists(JsonObject root, String field, String configFile, Object defaultValue) {
        if (!root.has(field)) {
            logMigration(configFile + ": 缺少字段 '" + field + "'，已使用默认值 " + defaultValue + " 并回写");
            return true;
        }
        return false;
    }

    /**
     * 检查配置文件的 _schema_version，若缺失或低于当前版本则记录迁移日志。
     * @return true 如果需要迁移（版本缺失或更低）
     */
    private static boolean checkSchemaVersion(JsonObject root, String configFile) {
        if (!root.has("_schema_version")) {
            logMigration(configFile + ": 缺少 _schema_version，视为 v1，迁移到 v" + CONFIG_SCHEMA_VERSION);
            return true;
        }
        int fileVersion = root.get("_schema_version").getAsInt();
        if (fileVersion < CONFIG_SCHEMA_VERSION) {
            logMigration(configFile + ": _schema_version v" + fileVersion + " → v" + CONFIG_SCHEMA_VERSION);
            return true;
        }
        return false;
    }

    // ======================== 文件夹化通用辅助 ========================

    /** 列出文件夹内所有 per-modid 的 *.json 文件（排除 _meta.json），按文件名排序保证加载顺序确定。 */
    private static List<Path> listModidJsonFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return files;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".json") && !name.equals("_meta.json")) files.add(p);
            });
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.warn("[FolderConfig] 列出 {} 失败: {}", dir, e.getMessage());
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /**
     * 将 items JsonObject 按 key 的 namespace（冒号前的部分）分组。
     * key 格式 "modid:path" → modid.json；无冒号的 key 归入 "default"。
     * 返回有序 Map（按 modid 字母序），方便稳定回写。
     */
    private static java.util.SortedMap<String, JsonObject> groupItemsByNamespace(JsonObject items) {
        java.util.SortedMap<String, JsonObject> grouped = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> e : items.entrySet()) {
            String key = e.getKey();
            String namespace = "default";
            int idx = key.indexOf(':');
            if (idx > 0) namespace = key.substring(0, idx);
            grouped.computeIfAbsent(namespace, k -> new JsonObject()).add(key, e.getValue());
        }
        return grouped;
    }

    /**
     * 将旧单文件 JSON 迁移到文件夹结构。
     * 仅当旧文件存在且文件夹不存在/为空时执行迁移：
     *   1. 读取旧文件 root，拆分为 meta（所有非 items/非 itemsKey/非 _schema_version 的顶层字段）+ items
     *   2. 写 _meta.json（含 _schema_version + meta 字段）
     *   3. 按 namespace 分组写 per-modid 文件（含 _schema_version + itemsKey）
     *   4. 旧文件改名 .bak
     * @param itemsKey items 对象在 root 中的键名（如 "items"/"weapon_distributions"/"entity_resistances"/"projectile_distributions"）
     * @return true 表示执行了迁移
     */
    private static boolean migrateLegacyFileToFolder(Path legacyFile, Path dir, String configName, String itemsKey) {
        if (!Files.exists(legacyFile)) return false;
        // 文件夹已有 per-modid 文件 → 不迁移（用户可能已手动放置文件夹）
        if (!listModidJsonFiles(dir).isEmpty()) {
            DamageOverhaulMod.LOGGER.info("[FolderMigration] {} 文件夹已存在内容，跳过旧文件迁移: {}", configName, dir);
            return false;
        }
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            JsonObject root;
            try (Reader reader = new InputStreamReader(Files.newInputStream(legacyFile), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            // 拆分：meta（顶层字段，排除 itemsKey 和 _schema_version）+ items
            JsonObject meta = new JsonObject();
            JsonObject items = new JsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String k = e.getKey();
                if (k.equals(itemsKey) && e.getValue().isJsonObject()) {
                    items = e.getValue().getAsJsonObject();
                } else if (!k.equals("_schema_version")) {
                    meta.add(k, e.getValue());
                }
            }
            // 写 _meta.json
            JsonObject metaRoot = new JsonObject();
            metaRoot.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
            for (Map.Entry<String, JsonElement> e : meta.entrySet()) metaRoot.add(e.getKey(), e.getValue());
            Path metaPath = dir.resolve("_meta.json");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(metaPath), StandardCharsets.UTF_8)) {
                GSON.toJson(metaRoot, w);
            }
            // 按 namespace 分组写 per-modid 文件
            java.util.SortedMap<String, JsonObject> grouped = groupItemsByNamespace(items);
            for (Map.Entry<String, JsonObject> e : grouped.entrySet()) {
                JsonObject fileRoot = new JsonObject();
                fileRoot.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
                fileRoot.add(itemsKey, e.getValue());
                Path p = dir.resolve(e.getKey() + ".json");
                try (Writer w = new OutputStreamWriter(Files.newOutputStream(p), StandardCharsets.UTF_8)) {
                    GSON.toJson(fileRoot, w);
                }
            }
            // 旧文件改名 .bak（不删除，保留用户可恢复）
            Path bakPath = legacyFile.resolveSibling(legacyFile.getFileName().toString() + ".bak");
            Files.move(legacyFile, bakPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logMigration(configName + ": 旧单文件已迁移到文件夹结构（_meta.json + " + grouped.size() + " 个 per-modid 文件），旧文件改名 .bak");
            return true;
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("[FolderMigration] 迁移 {} 失败", configName, e);
            return false;
        }
    }

    /**
     * 读取文件夹结构配置，返回 {meta, items}。
     *   meta：来自 _meta.json 的全部顶层字段（含 _schema_version）
     *   items：所有 per-modid 文件中 itemsKey 对象的并集（重复 key 保留第一条并记日志）
     * 文件夹不存在时返回两个空 JsonObject。
     */
    private static JsonObject[] readFolderConfig(Path dir, String itemsKey, String configName) {
        JsonObject meta = new JsonObject();
        JsonObject items = new JsonObject();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return new JsonObject[]{meta, items};
        }
        // 读 _meta.json
        Path metaPath = dir.resolve("_meta.json");
        if (Files.exists(metaPath)) {
            try (Reader r = new InputStreamReader(Files.newInputStream(metaPath), StandardCharsets.UTF_8)) {
                meta = JsonParser.parseReader(r).getAsJsonObject();
            } catch (Exception e) {
                DamageOverhaulMod.LOGGER.warn("[FolderConfig] 读取 {} 失败: {}", metaPath, e.getMessage());
            }
        }
        // 读 per-modid 文件
        for (Path p : listModidJsonFiles(dir)) {
            try (Reader r = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                if (root.has(itemsKey) && root.get(itemsKey).isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(itemsKey).entrySet()) {
                        if (!items.has(e.getKey())) {
                            items.add(e.getKey(), e.getValue());
                        } else {
                            logMigration(configName + ": " + p.getFileName() + " 中 '" + e.getKey() + "' 重复，保留第一条");
                        }
                    }
                }
            } catch (Exception e) {
                DamageOverhaulMod.LOGGER.warn("[FolderConfig] 读取 {} 失败: {}", p, e.getMessage());
            }
        }
        return new JsonObject[]{meta, items};
    }

    /**
     * 将配置写回文件夹结构：_meta.json + per-modid 文件。
     *   meta：顶层元数据字段（caller 构造好的 JsonObject，含 _schema_version）
     *   items：全部条目的合并 JsonObject
     *   itemsKey：items 对象的键名
     * 回写前会删除所有旧 per-modid 文件（避免已移除模组的残留文件），但不动 _meta.json。
     */
    private static void writeFolderConfig(Path dir, JsonObject meta, JsonObject items, String itemsKey, String configName) {
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            // 写 _meta.json
            Path metaPath = dir.resolve("_meta.json");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(metaPath), StandardCharsets.UTF_8)) {
                GSON.toJson(meta, w);
            }
            // 删除旧 per-modid 文件（避免已卸载模组的残留文件）
            for (Path p : listModidJsonFiles(dir)) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
            // 按 namespace 分组写
            java.util.SortedMap<String, JsonObject> grouped = groupItemsByNamespace(items);
            for (Map.Entry<String, JsonObject> e : grouped.entrySet()) {
                JsonObject fileRoot = new JsonObject();
                fileRoot.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
                fileRoot.add(itemsKey, e.getValue());
                Path p = dir.resolve(e.getKey() + ".json");
                try (Writer w = new OutputStreamWriter(Files.newOutputStream(p), StandardCharsets.UTF_8)) {
                    GSON.toJson(fileRoot, w);
                }
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("[FolderConfig] 写入 {} 失败: {}", configName, e.getMessage());
        }
    }

    // ======================== 默认实体抗性（全部0，玩家可自行在配置中修改） ========================
    private static final Map<String, Map<String, Float>> DEFAULT_ENTITY_RESISTANCES = new HashMap<>();

    private static void putDefaultResistance(String entityPath, String damageType, float value) {
        DEFAULT_ENTITY_RESISTANCES.computeIfAbsent(entityPath, k -> new HashMap<>()).put(damageType, value);
    }

    // ======================== 默认空手攻击分布 ========================
    private static final Map<String, Map<String, Float>> DEFAULT_UNARMED_DISTRIBUTIONS = new HashMap<>();

    static {
        // 特殊空手攻击类型
        putDefaultUnarmed("creeper", "fire", 0.5f);  putDefaultUnarmed("creeper", "blunt", 0.5f);
        putDefaultUnarmed("blaze", "fire", 1.0f);
        putDefaultUnarmed("ghast", "fire", 1.0f);
        putDefaultUnarmed("guardian", "magic", 1.0f);
        putDefaultUnarmed("elder_guardian", "magic", 1.0f);
        putDefaultUnarmed("shulker", "piercing", 1.0f);
        putDefaultUnarmed("vex", "slashing", 1.0f);
        putDefaultUnarmed("evoker", "magic", 1.0f);
        putDefaultUnarmed("wither", "magic", 1.0f);
        putDefaultUnarmed("warden", "blunt", 0.5f);  putDefaultUnarmed("warden", "magic", 0.5f);
        putDefaultUnarmed("ender_dragon", "blunt", 0.5f);  putDefaultUnarmed("ender_dragon", "magic", 0.5f);
        putDefaultUnarmed("spider", "piercing", 0.7f);  putDefaultUnarmed("spider", "blunt", 0.3f);
        putDefaultUnarmed("cave_spider", "piercing", 0.7f);  putDefaultUnarmed("cave_spider", "blunt", 0.3f);
        putDefaultUnarmed("magma_cube", "fire", 0.5f);  putDefaultUnarmed("magma_cube", "blunt", 0.5f);
    }

    private static void putDefaultUnarmed(String entityPath, String damageType, float value) {
        DEFAULT_UNARMED_DISTRIBUTIONS.computeIfAbsent(entityPath, k -> new HashMap<>()).put(damageType, value);
    }

    // ======================== 配置加载入口 ========================
    public static void onServerStarting(ServerStartingEvent event) {
        scanLevel = event.getServer().overworld();
        loadAllConfigs();
        // 注册表此时已加载（DamageType 为 datapack registry），自动补齐未映射的伤害类型（含模组新增）
        syncDamageTypeMappingsWithRegistry(event.getServer());
    }

    /** 注册 /damageoverhaul 命令树 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(
                net.minecraft.commands.Commands.literal("damageoverhaul")
                        .requires(src -> src.hasPermission(2))
                        .then(net.minecraft.commands.Commands.literal("reload")
                                .executes(ctx -> executeReload(ctx, false)))
                        .then(net.minecraft.commands.Commands.literal("migrate")
                                .executes(ctx -> executeReload(ctx, true)))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> {
                                    // 统计射弹配置
                                    int projTotal = PROJECTILE_CACHE.size();
                                    int projFollow = 0, projOverride = 0;
                                    StringBuilder overrideList = new StringBuilder();
                                    for (Map.Entry<ResourceLocation, ProjectileConfig> e : PROJECTILE_CACHE.entrySet()) {
                                        if (e.getValue().followDefault) {
                                            projFollow++;
                                        } else {
                                            projOverride++;
                                            String actual = "weapon".equals(rangedDamageSource) ? "projectile" : "weapon";
                                            overrideList.append("\n    - ").append(e.getKey())
                                                    .append(" (拒绝默认→").append(actual).append(")");
                                        }
                                    }
                                    String projSummary = projTotal + " 个射弹 (遵循默认: " + projFollow
                                            + ", 拒绝默认: " + projOverride + ")"
                                            + (projOverride > 0 ? overrideList : "");
                                    // 射弹替换统计
                                    int replaceCount = 0;
                                    StringBuilder replaceList = new StringBuilder();
                                    for (Map.Entry<ResourceLocation, ProjectileItemMapping> e : PROJ_ITEM_MAP.entrySet()) {
                                        if (e.getValue().replaceEntity) {
                                            replaceCount++;
                                            replaceList.append("\n    - ").append(e.getKey())
                                                    .append(" → ").append(e.getValue().entityId);
                                        }
                                    }
                                    String replaceSummary = (allowDangerousReplace ? "允许" : "禁止")
                                            + " (映射: " + PROJ_ITEM_MAP.size() + ", 替换实体: " + replaceCount + ")"
                                            + (replaceCount > 0 ? replaceList : "");
                                    // 附魔统计
                                    int protectCount = ModEnchantments.PROTECTION_ENCHANTMENTS.size();
                                    int strikeCount = ModEnchantments.STRIKE_ENCHANTMENTS.size();
                                    int projStrikeCount = ModEnchantments.PROJECTILE_STRIKE_ENCHANTMENTS.size();
                                    ctx.getSource().sendSuccess(
                                            () -> net.minecraft.network.chat.Component.literal(
                                                    "[DamageOverhaul] 状态:\n" +
                                                            "  伤害类型 (" + damageTypes.size() + "): " + damageTypes + "\n" +
                                                            "  已注册附魔: 抗性x" + protectCount
                                                            + ", 武器打击x" + strikeCount
                                                            + ", 射弹打击x" + projStrikeCount
                                                            + " (等级上限: " + maxEnchantLevel + ")\n" +
                                                            "  药水效果: " + (mobEffectsEnabled ? "启用" : "禁用") +
                                                            " (" + MOB_EFFECT_CACHE.size() + " 类型 x 4效果: 抗性±/伤害±)\n" +
                                                            "  装备补正: 武器=" + (weaponScalingEnabled ? "启用" : "禁用") +
                                                            " (" + WEAPON_SCALING_CACHE.size() + " 件)" +
                                                            ", 盔甲=" + (armorScalingEnabled ? "启用" : "禁用") +
                                                            " (" + ARMOR_SCALING_CACHE.size() + " 件)" +
                                                            ", 能力列=" + scalingAttributes.size() +
                                                            ", more_attributes=" + (com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isPresent() ? "已装载(外部源)" : "未装载") +
                                                            ", 内部属性=" + (com.zerochen.damageoverhaul.integration.MoreAttributesBridge.isPresent() ? "休眠" : (internalAttributesEnabled ? "启用(" + INTERNAL_ATTRIBUTES.size() + "能力)" : "禁用")) + "\n" +
                                                            "  弹开机制: " + (deflectionEnabled ? "启用" : "禁用") +
                                                            " (X=" + deflectionX + ", Z=" + deflectionZ + ")\n" +
                                                            "  弹开影响类型: " + deflectionAffectedTypes + "\n" +
                                                            "  远程伤害全局默认来源: " + rangedDamageSource
                                                            + " (weapon=武器分布, projectile=射弹分布)\n" +
                                                            "  射弹配置: " + projSummary + "\n" +
                                                            "  射弹替换: " + replaceSummary + "\n" +
                                                            "  魔力修复: " + (manaRepairEnabled ? "启用" : "禁用") +
                                                            " (静态: " + (staticRepairEnabled ? "开" : "关") +
                                                            ", 间隔: " + staticRepairInterval + "t" +
                                                            ", 每次: " + staticRepairPerTick + "点)\n" +
                                                            "  自家饰品: " + CHARM_CACHE.size() + " 个\n" +
                                                            "  第三方饰品加成: " + (externalCharmsEnabled ? "启用" : "禁用") +
                                                            " (条目: " + EXTERNAL_CHARM_CACHE.size()
                                                            + ", 启动扫描: " + (externalCharmsAutoScan ? "开" : "关")
                                                            + ", 装备时补扫: 开)\n" +
                                                            "  配置架构版本: v" + CONFIG_SCHEMA_VERSION + "\n" +
                                                            "  调试日志: " + (debugLoggingEnabled ? "开启（排查配置时使用，会刷屏）" : "关闭")),
                                            false);
                                    return 1;
                                }))
                        .then(net.minecraft.commands.Commands.literal("migrations")
                                .executes(ctx -> {
                                    if (MIGRATION_LOG.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> net.minecraft.network.chat.Component.literal(
                                                        "[DamageOverhaul] 上次加载未执行任何自动迁移。"),
                                                false);
                                    } else {
                                        StringBuilder sb = new StringBuilder();
                                        sb.append("[DamageOverhaul] 上次加载执行了 ")
                                                .append(MIGRATION_LOG.size()).append(" 项自动迁移:\n");
                                        for (int i = 0; i < MIGRATION_LOG.size(); i++) {
                                            sb.append("  ").append(i + 1).append(". ").append(MIGRATION_LOG.get(i)).append("\n");
                                        }
                                        if (!migrationTimestamp.isEmpty()) {
                                            sb.append("备份目录: config/damageoverhaul/backups/")
                                                    .append(migrationTimestamp).append("/");
                                        }
                                        ctx.getSource().sendSuccess(
                                                () -> net.minecraft.network.chat.Component.literal(sb.toString()),
                                                false);
                                    }
                                    return 1;
                                }))
        );
    }

    /** 执行配置重载/迁移 */
    private static int executeReload(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, boolean isMigrate) {
        try {
            loadAllConfigs();
            // 重载时同样执行注册表扫描（补齐新增伤害类型）
            int autoAdded = syncDamageTypeMappingsWithRegistry(ctx.getSource().getServer());
            StringBuilder sb = new StringBuilder();
            if (isMigrate) {
                sb.append("[DamageOverhaul] 配置迁移检查完成。");
            } else {
                sb.append("[DamageOverhaul] 配置已热重载并自动同步。");
            }
            sb.append(" 伤害类型: ").append(damageTypes.size())
              .append(" | 实体抗性: ").append(RESISTANCE_CACHE.size())
              .append(" | 武器分布: ").append(WEAPON_CACHE.size())
              .append(" | 护甲抗性: ").append(ARMOR_CACHE.size())
              .append(" | 空手分布: ").append(UNARMED_CACHE.size())
              .append(" | 射弹分布: ").append(PROJECTILE_CACHE.size())
              .append(" | 魔力修复物品: ").append(MANA_REPAIR_CACHE.size())
              .append(" | 方块分布: ").append(BLOCK_DIST_CACHE.size())
              .append(" | 伤害类型映射: ").append(DAMAGE_TYPE_MAPPINGS.size())
              .append(" (自动扫描新增: ").append(autoAdded).append(")")
              .append(" | 自家饰品: ").append(CHARM_CACHE.size())
              .append(" | 第三方饰品: ").append(EXTERNAL_CHARM_CACHE.size())
              .append(" | 药水效果行: ").append(MOB_EFFECT_CACHE.size())
              .append(" | 武器补正: ").append(WEAPON_SCALING_CACHE.size())
              .append(" | 盔甲补正: ").append(ARMOR_SCALING_CACHE.size());

            if (hasMigrationOccurred()) {
                sb.append("\n§e[迁移] 本次共执行 ").append(MIGRATION_LOG.size())
                  .append(" 项自动修改，原配置已备份到 config/damageoverhaul/backups/")
                  .append(migrationTimestamp).append("/");
                sb.append("\n§e[迁移] 输入 /damageoverhaul migrations 查看详细迁移日志。");
            } else if (isMigrate) {
                sb.append("\n§a所有配置文件均为最新版本，无需迁移。");
            }

            ctx.getSource().sendSuccess(
                    () -> net.minecraft.network.chat.Component.literal(sb.toString()), true);
        } catch (Exception e) {
            ctx.getSource().sendFailure(
                    net.minecraft.network.chat.Component.literal(
                            "[DamageOverhaul] 操作失败: " + e.getMessage()));
            DamageOverhaulMod.LOGGER.error("Config reload/migrate failed", e);
            return 0;
        }
        return 1;
    }

    /** 加载全部配置文件（服务端 + 客户端 tooltip 均可调用） */
    public static void loadAllConfigs() {
        resetMigrationLog();
        loadOrCreateDamageTypes();
        loadOrCreateMappingsConfig();
        loadOrCreateResistanceConfig();
        loadOrCreateWeaponConfig();
        loadOrCreateUnarmedConfig();
        loadOrCreateArmorConfig();
        loadOrCreateRangedConfig();
        loadOrCreateMechanicsConfig();
        loadOrCreateManaRepairConfig();
        loadOrCreateProjectileStrikeConfig();
        loadOrCreateDisplayConfig();
        loadOrCreateBlockDistConfig();
        loadOrCreateProjectileItemMap();
        loadOrCreateCharmsConfig();
        loadOrCreateExternalCharmsConfig();
        loadOrCreateMobEffectsConfig();
        // 必须先于补正表加载：补正的能力列名/baseLevel 来自内部属性配置（外部模组缺席时）
        loadOrCreateInternalAttributesConfig();
        loadOrCreateScalingConfigs();
        // 冲击力/韧性系统配置
        loadOrCreateWeaponGuardBreakConfig();
        loadOrCreateArmorToughnessConfig();
        loadOrCreatePlayerConfig();
        loadOrCreateImpactEffectsConfig();
        if (hasMigrationOccurred()) {
            DamageOverhaulMod.LOGGER.info("[ConfigMigration] 本次加载共执行 {} 项自动迁移。备份目录: {}",
                    MIGRATION_LOG.size(), BACKUP_DIR.resolve(migrationTimestamp));
        }
    }

    // ======================== 伤害类型配置 ========================
    private static void loadOrCreateDamageTypes() {
        try {
            if (!Files.exists(CONFIG_PATH)) Files.createDirectories(CONFIG_PATH);

            if (!Files.exists(DAMAGE_TYPES_FILE)) {
                damageTypes = new ArrayList<>(DEFAULT_DAMAGE_TYPES);
                maxEnchantLevel = DEFAULT_MAX_ENCHANT_LEVEL;
                saveDamageTypes();
                DamageOverhaulMod.LOGGER.info("Generated default damage types config: {}", DAMAGE_TYPES_FILE);
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(DAMAGE_TYPES_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "damage_types.json")) needUpdate = true;

                if (root.has("max_enchant_level")) {
                    maxEnchantLevel = Math.max(1, root.get("max_enchant_level").getAsInt());
                } else {
                    maxEnchantLevel = DEFAULT_MAX_ENCHANT_LEVEL;
                    logMigration("damage_types.json: 缺少 max_enchant_level 字段，已补写默认值 " + DEFAULT_MAX_ENCHANT_LEVEL
                            + "（附魔等级上限解耦，允许超高级附魔按线性公式生效）");
                    needUpdate = true;
                }

                damageTypes.clear();
                if (root.has("damage_types") && root.get("damage_types").isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray("damage_types")) {
                        String name = e.getAsString();
                        if (!name.isEmpty() && !damageTypes.contains(name)) damageTypes.add(name);
                    }
                }
                if (damageTypes.isEmpty()) {
                    damageTypes = new ArrayList<>(DEFAULT_DAMAGE_TYPES);
                    logMigration("damage_types.json: 伤害类型列表为空，已重置为默认值");
                    needUpdate = true;
                }
                if (needUpdate) {
                    backupBeforeSave(DAMAGE_TYPES_FILE, "damage types migration");
                    saveDamageTypes();
                }
            }
            DamageOverhaulMod.LOGGER.info("Loaded damage types: {}", damageTypes);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load damage types config", e);
            damageTypes = new ArrayList<>(DEFAULT_DAMAGE_TYPES);
        }
    }

    private static void saveDamageTypes() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment_max_enchant_level", "附魔等级上限（protect_/strike_/projectile_strike_ 全系列共用，默认255）。"
                + "数值公式保持线性：N级 = N × 每级值，超高级附魔（铁砧叠加/指令）正常生效。修改后需重启游戏");
        root.addProperty("max_enchant_level", maxEnchantLevel);
        root.add("damage_types", GSON.toJsonTree(damageTypes));
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(DAMAGE_TYPES_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save damage types config", e);
        }
    }

    // ======================== 伤害类型映射配置 ========================
    // 将原版/模组的 DamageType（或标签）映射到本模组的伤害类型，
    // 使其经过实体抗性 / 护甲抗性的计算。
    // 键格式：
    //   "modid:path"   → 匹配具体的伤害类型 ID
    //   "#modid:tagpath" → 匹配伤害类型标签
    // 值：必须是在 damage_types.json 中已存在的伤害类型名
    private static void loadOrCreateMappingsConfig() {
        try {
            if (!Files.exists(MAPPINGS_FILE)) {
                applyDefaultAutoInferRules();
                generateDefaultMappings();
                DamageOverhaulMod.LOGGER.info("Generated default damage type mappings: {}", MAPPINGS_FILE);
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(MAPPINGS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject mappings = root.getAsJsonObject("damage_type_mappings");

                boolean needUpdate = false;
                if (checkSchemaVersion(root, "damage_type_mappings.json")) needUpdate = true;

                // 解析自动推断配置（缺失时补默认并回写）
                applyDefaultAutoInferRules();
                if (root.has("auto_infer_enabled")) {
                    autoInferEnabled = root.get("auto_infer_enabled").getAsBoolean();
                } else {
                    needUpdate = true;
                }
                if (root.has("auto_infer_rules") && root.get("auto_infer_rules").isJsonObject()) {
                    AUTO_INFER_RULES.clear();
                    JsonObject rules = root.getAsJsonObject("auto_infer_rules");
                    for (Map.Entry<String, JsonElement> e : rules.entrySet()) {
                        AUTO_INFER_RULES.put(e.getKey(), e.getValue().getAsString());
                    }
                } else {
                    needUpdate = true;
                }

                DAMAGE_TYPE_MAPPINGS.clear();

                if (mappings != null) {
                    for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                        String key = entry.getKey();
                        JsonElement val = entry.getValue();
                        Map<String, Float> dist = new LinkedHashMap<>();

                        if (val.isJsonPrimitive()) {
                            // 旧格式（String）：自动迁移为 {type: 1.0}
                            String typeName = val.getAsString();
                            if (!damageTypes.contains(typeName)) {
                                DamageOverhaulMod.LOGGER.warn("Mapping '{}' -> '{}': target type not in damage_types, skipped.", key, typeName);
                                logMigration("damage_type_mappings.json: 映射 " + key + " -> " + typeName + " 的目标伤害类型不存在，已跳过");
                                needUpdate = true;
                                continue;
                            }
                            dist.put(typeName, 1.0f);
                            logMigration("damage_type_mappings.json: 迁移 " + key + " 从 String→混合分布格式");
                            needUpdate = true;
                        } else if (val.isJsonObject()) {
                            // 新格式（JsonObject）：混合分布 {type: ratio, ...}
                            JsonObject obj = val.getAsJsonObject();
                            for (Map.Entry<String, JsonElement> distEntry : obj.entrySet()) {
                                String typeName = distEntry.getKey();
                                float ratio = distEntry.getValue().getAsFloat();
                                if (!damageTypes.contains(typeName)) {
                                    DamageOverhaulMod.LOGGER.warn("Mapping '{}' sub-type '{}': not in damage_types, skipped.", key, typeName);
                                    logMigration("damage_type_mappings.json: 映射 " + key + " 的子类型 " + typeName + " 不存在，已跳过");
                                    needUpdate = true;
                                    continue;
                                }
                                if (ratio <= 0f) continue;
                                dist.put(typeName, ratio);
                            }
                        }

                        if (!dist.isEmpty()) {
                            DAMAGE_TYPE_MAPPINGS.put(key, dist);
                        }
                    }
                }

                // 补齐默认映射中缺失的条目
                for (Map.Entry<String, Map<String, Float>> e : getDefaultMappings().entrySet()) {
                    if (!DAMAGE_TYPE_MAPPINGS.containsKey(e.getKey())) {
                        // 验证默认分布中所有类型都存在
                        Map<String, Float> filtered = new LinkedHashMap<>();
                        for (Map.Entry<String, Float> dist : e.getValue().entrySet()) {
                            if (damageTypes.contains(dist.getKey())) {
                                filtered.put(dist.getKey(), dist.getValue());
                            }
                        }
                        if (!filtered.isEmpty()) {
                            DAMAGE_TYPE_MAPPINGS.put(e.getKey(), filtered);
                            logMigration("damage_type_mappings.json: 新增默认映射 " + e.getKey());
                            needUpdate = true;
                        }
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(MAPPINGS_FILE, "mappings migration");
                    saveMappingsConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded {} damage type mappings.", DAMAGE_TYPE_MAPPINGS.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load damage type mappings", e);
        }
    }

    /** 自动推断规则的默认值（可被配置文件覆盖） */
    private static void applyDefaultAutoInferRules() {
        AUTO_INFER_RULES.clear();
        AUTO_INFER_RULES.put("#minecraft:is_fire", "fire");
        AUTO_INFER_RULES.put("#minecraft:is_lightning", "lightning");
        AUTO_INFER_RULES.put("#minecraft:is_explosion", "blunt");
        AUTO_INFER_RULES.put("#minecraft:is_fall", "blunt");
        AUTO_INFER_RULES.put("#minecraft:is_drowning", "blunt");
        AUTO_INFER_RULES.put("#minecraft:is_freezing", "magic");
        AUTO_INFER_RULES.put("#minecraft:is_projectile", "piercing");
        AUTO_INFER_RULES.put("*", "blunt"); // 无标签匹配时的兜底
        autoInferEnabled = true;
    }

    /** 辅助方法：创建单类型分布（ratio=1.0） */
    private static Map<String, Float> singleDist(String type) {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put(type, 1.0f);
        return m;
    }

    /** 辅助方法：创建混合分布（varargs 交替传入 type, ratio, type, ratio...） */
    private static Map<String, Float> mixedDist(Object... pairs) {
        Map<String, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], ((Number) pairs[i + 1]).floatValue());
        }
        return m;
    }

    private static Map<String, Map<String, Float>> getDefaultMappings() {
        Map<String, Map<String, Float>> m = new LinkedHashMap<>();
        // 标签映射（#开头）
        m.put("#minecraft:is_fire", singleDist("fire"));
        m.put("#minecraft:is_lightning", singleDist("lightning"));
        m.put("#minecraft:is_drowning", singleDist("blunt"));      // 溺水归为钝击（可改）
        m.put("#minecraft:is_freezing", singleDist("magic"));      // 冰冻归为魔法（可改）
        m.put("#minecraft:is_falling", singleDist("blunt"));        // 摔伤归为钝击
        // 具体伤害类型映射
        m.put("minecraft:in_fire", singleDist("fire"));
        m.put("minecraft:on_fire", singleDist("fire"));
        m.put("minecraft:lava", singleDist("fire"));
        m.put("minecraft:hot_floor", singleDist("fire"));
        m.put("minecraft:fire", singleDist("fire"));
        m.put("minecraft:lightning_bolt", singleDist("lightning"));
        m.put("minecraft:magic", singleDist("magic"));
        m.put("minecraft:wither", singleDist("dark"));
        m.put("minecraft:dragon_breath", singleDist("magic"));
        m.put("minecraft:cactus", singleDist("piercing"));
        m.put("minecraft:stalagmite", singleDist("piercing"));
        m.put("minecraft:falling_stalactite", singleDist("blunt"));
        m.put("minecraft:fall", singleDist("blunt"));
        m.put("minecraft:stalactite", singleDist("blunt"));
        m.put("minecraft:fly_into_wall", singleDist("blunt"));
        m.put("minecraft:anvil", singleDist("blunt"));
        m.put("minecraft:cramming", singleDist("blunt"));
        m.put("minecraft:falling_block", singleDist("blunt"));
        m.put("minecraft:drown", singleDist("blunt"));
        m.put("minecraft:starve", singleDist("blunt"));
        m.put("minecraft:in_wall", singleDist("blunt"));
        m.put("minecraft:sweet_berry_bush", singleDist("piercing"));
        m.put("minecraft:arrow", singleDist("piercing"));
        m.put("minecraft:trident", singleDist("piercing"));
        m.put("minecraft:sting", singleDist("piercing"));
        m.put("minecraft:thorns", singleDist("magic"));
        m.put("minecraft:out_of_world", singleDist("magic"));
        m.put("minecraft:generic", singleDist("blunt"));
        m.put("minecraft:explosion", singleDist("blunt"));  // 爆炸（TNT/末影水晶等）归为钝击
        m.put("minecraft:wither_skull", singleDist("dark"));
        m.put("minecraft:indirect_magic", singleDist("magic"));
        m.put("minecraft:mob_attack", singleDist("blunt"));
        m.put("minecraft:player_attack", singleDist("blunt"));
        return m;
    }

    private static void generateDefaultMappings() {
        DAMAGE_TYPE_MAPPINGS.clear();
        for (Map.Entry<String, Map<String, Float>> e : getDefaultMappings().entrySet()) {
            // 验证分布中所有类型都存在
            Map<String, Float> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Float> dist : e.getValue().entrySet()) {
                if (damageTypes.contains(dist.getKey())) {
                    filtered.put(dist.getKey(), dist.getValue());
                }
            }
            if (!filtered.isEmpty()) {
                DAMAGE_TYPE_MAPPINGS.put(e.getKey(), filtered);
            }
        }
        saveMappingsConfig();
    }

    private static void saveMappingsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("auto_infer_enabled", autoInferEnabled);
        JsonObject rules = new JsonObject();
        for (Map.Entry<String, String> e : AUTO_INFER_RULES.entrySet()) {
            rules.addProperty(e.getKey(), e.getValue());
        }
        root.add("auto_infer_rules", rules);
        JsonObject map = new JsonObject();
        for (Map.Entry<String, Map<String, Float>> e : DAMAGE_TYPE_MAPPINGS.entrySet()) {
            Map<String, Float> dist = e.getValue();
            if (dist.size() == 1) {
                Map.Entry<String, Float> single = dist.entrySet().iterator().next();
                if (single.getValue() == 1.0f) {
                    // 单类型 + ratio=1.0 → 保存为 String（与旧格式兼容，更简洁）
                    map.addProperty(e.getKey(), single.getKey());
                } else {
                    // 单类型 + ratio≠1.0 → 保存为 JsonObject
                    JsonObject obj = new JsonObject();
                    obj.addProperty(single.getKey(), single.getValue());
                    map.add(e.getKey(), obj);
                }
            } else {
                // 多类型混合分布 → 保存为 JsonObject
                JsonObject obj = new JsonObject();
                for (Map.Entry<String, Float> distEntry : dist.entrySet()) {
                    obj.addProperty(distEntry.getKey(), distEntry.getValue());
                }
                map.add(e.getKey(), obj);
            }
        }
        root.add("damage_type_mappings", map);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(MAPPINGS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save damage type mappings", e);
        }
    }

    /**
     * 查询 DamageSource 映射到的本模组伤害类型（主导类型，即占比最高的类型）。
     * 先匹配具体伤害类型 ID，再匹配标签。
     * @return 映射到的主导伤害类型名，或 null（无映射）
     */
    public static String getMappedDamageType(DamageSource source) {
        Map<String, Float> dist = getMappedDamageDistribution(source);
        if (dist == null || dist.isEmpty()) return null;
        // 返回占比最高的类型
        String dominant = null;
        float maxRatio = -1f;
        for (Map.Entry<String, Float> e : dist.entrySet()) {
            if (e.getValue() > maxRatio) {
                maxRatio = e.getValue();
                dominant = e.getKey();
            }
        }
        return dominant;
    }

    /**
     * 查询 DamageSource 映射到的混合伤害分布。
     * 先匹配具体伤害类型 ID，再匹配标签。
     * @return 混合分布 Map（伤害类型→绝对占比），或 null（无映射）
     */
    public static Map<String, Float> getMappedDamageDistribution(DamageSource source) {
        // 1. 具体伤害类型 ID
        ResourceLocation typeId = source.typeHolder().unwrapKey()
                .map(k -> k.location()).orElse(null);
        if (typeId != null) {
            Map<String, Float> mapped = DAMAGE_TYPE_MAPPINGS.get(typeId.toString());
            if (mapped != null) return mapped;
        }

        // 2. 标签匹配
        for (Map.Entry<String, Map<String, Float>> entry : DAMAGE_TYPE_MAPPINGS.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("#")) continue;
            String[] parts = key.substring(1).split(":", 2);
            if (parts.length != 2) continue;
            TagKey<DamageType> tag = TagKey.create(
                    net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    new ResourceLocation(parts[0], parts[1]));
            if (source.is(tag)) return entry.getValue();
        }

        return null;
    }

    public static Map<String, Map<String, Float>> getDamageTypeMappings() {
        return DAMAGE_TYPE_MAPPINGS;
    }

    /**
     * 自动扫描伤害类型注册表（datapack registry，含所有模组新增的伤害类型），
     * 为所有未映射的伤害类型按 auto_infer_rules 标签规则推断映射并写入配置文件。
     *
     * <p>调用时机：必须在注册表已加载后（ServerStartingEvent / reload 命令），
     * 因为 DamageType 是 datapack registry，FMLCommonSetup 阶段不可用。</p>
     *
     * @param server 当前 MinecraftServer（提供 registryAccess）
     * @return 本次扫描新增的映射数量
     */
    public static int syncDamageTypeMappingsWithRegistry(net.minecraft.server.MinecraftServer server) {
        int added = 0;
        try {
            if (!autoInferEnabled) return 0;

            // DAMAGE_TYPE 是 datapack registry，必须通过 RegistryAccess 获取
            var registry = server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);

            // 预解析推断规则的标签（仅解析合法的 # 开头键；"*" 为兜底规则）
            List<Map.Entry<TagKey<DamageType>, String>> tagRules = new ArrayList<>();
            String fallbackType = null;
            for (Map.Entry<String, String> rule : AUTO_INFER_RULES.entrySet()) {
                String key = rule.getKey();
                if ("*".equals(key)) {
                    fallbackType = rule.getValue();
                    continue;
                }
                if (!key.startsWith("#")) continue;
                String[] parts = key.substring(1).split(":", 2);
                if (parts.length != 2) continue;
                tagRules.add(new AbstractMap.SimpleEntry<>(
                        TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(parts[0], parts[1])),
                        rule.getValue()));
            }

            for (Holder.Reference<DamageType> holder : registry.holders().toList()) {
                ResourceLocation typeId = holder.unwrapKey()
                        .map(k -> k.location()).orElse(null);
                if (typeId == null) continue;
                String key = typeId.toString();
                if (DAMAGE_TYPE_MAPPINGS.containsKey(key)) continue; // 已有映射（含手动配置）不覆盖

                String inferred = null;

                // 1. 按规则顺序匹配标签
                for (Map.Entry<TagKey<DamageType>, String> rule : tagRules) {
                    if (holder.is(rule.getKey())) {
                        inferred = rule.getValue();
                        break;
                    }
                }
                // 2. 兜底规则（"*"）
                if (inferred == null) {
                    inferred = fallbackType;
                }

                if (inferred == null || !damageTypes.contains(inferred)) continue;

                Map<String, Float> dist = new LinkedHashMap<>();
                dist.put(inferred, 1.0f);
                DAMAGE_TYPE_MAPPINGS.put(key, dist);
                logMigration("damage_type_mappings.json: 自动扫描新增 " + key + " → " + inferred
                        + (holder.tags().findAny().isPresent() ? " (标签推断)" : " (无标签，兜底)"));
                added++;
            }

            if (added > 0) {
                backupBeforeSave(MAPPINGS_FILE, "auto scan damage types");
                saveMappingsConfig();
                DamageOverhaulMod.LOGGER.info("[AutoInfer] 扫描注册表共 {} 个伤害类型，新增 {} 条自动映射",
                        registry.size(), added);
            } else {
                DamageOverhaulMod.LOGGER.info("[AutoInfer] 扫描注册表共 {} 个伤害类型，无缺失映射",
                        registry.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("[AutoInfer] 自动扫描伤害类型注册表失败", e);
        }
        return added;
    }

    // ======================== 实体抗性配置（文件夹化） ========================
    private static void loadOrCreateResistanceConfig() {
        try {
            // 旧单文件迁移到文件夹结构
            migrateLegacyFileToFolder(ENTITY_RESISTANCES_LEGACY, ENTITY_RESISTANCES_DIR, "entity_resistances", "entity_resistances");

            JsonObject[] result = readFolderConfig(ENTITY_RESISTANCES_DIR, "entity_resistances", "entity_resistances");
            JsonObject meta = result[0];
            JsonObject entities = result[1];

            RESISTANCE_CACHE.clear();
            boolean needUpdate = false;
            // 文件夹首次创建（无 _meta.json）时生成默认
            boolean firstCreate = (meta.size() == 0 && entities.size() == 0);
            if (firstCreate) {
                generateDefaultResistanceConfig();
                DamageOverhaulMod.LOGGER.info("Generated default resistance config folder: {}", ENTITY_RESISTANCES_DIR);
                return;
            }
            if (checkSchemaVersion(meta, "entity_resistances")) needUpdate = true;

            if (entities != null) {
                for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                    ResourceLocation id = new ResourceLocation(entry.getKey());
                    JsonObject data = entry.getValue().getAsJsonObject();
                    String displayName = data.has("display_name") ? data.get("display_name").getAsString() : id.getPath();
                    String translationKey = data.has("translation_key") ? data.get("translation_key").getAsString() : "";
                    JsonObject modifiers = data.getAsJsonObject("modifiers");
                    if (modifiers == null) { modifiers = new JsonObject(); }

                    // 同步：移除已删除的伤害类型
                    Set<String> toRemove = new HashSet<>();
                    for (String key : modifiers.keySet()) if (!damageTypes.contains(key)) toRemove.add(key);
                    for (String key : toRemove) {
                        logMigration("entity_resistances: 实体 " + id + " 移除已删除的伤害类型 '" + key + "'");
                        needUpdate = true;
                    }
                    // 同步：补齐新增的伤害类型
                    for (String type : damageTypes) if (!modifiers.has(type)) needUpdate = true;

                    Map<String, Float> modifierMap = new HashMap<>();
                    for (String type : damageTypes) modifierMap.put(type, modifiers.has(type) ? modifiers.get(type).getAsFloat() : 0f);
                    EntityResistance er = new EntityResistance();
                    er.displayName = displayName;
                    er.translationKey = translationKey;
                    er.modifiers = modifierMap;
                    er.baseToughness = data.has("base_toughness") ? data.get("base_toughness").getAsFloat() : 0f;
                    er.immuneImpactEffects = readStringList(data, "immune_impact_effects");
                    er.impactEffectRemap = readStringMap(data, "impact_effect_remap");
                    // v4 迁移：移除旧的 base_impact_resistance 字段
                    if (data.has("base_impact_resistance")) {
                        logMigration("entity_resistances: 实体 " + id + " 移除旧版 base_impact_resistance 字段（已改为 immune_impact_effects + impact_effect_remap）");
                        needUpdate = true;
                    }
                    if (!data.has("base_toughness")) needUpdate = true;
                    if (!data.has("immune_impact_effects")) needUpdate = true;
                    if (!data.has("impact_effect_remap")) needUpdate = true;
                    RESISTANCE_CACHE.put(id, er);
                }
            }

            // 检查新增生物
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (id == null || RESISTANCE_CACHE.containsKey(id)) continue;
                Map<String, Float> modifierMap = new HashMap<>();
                Map<String, Float> defaults = DEFAULT_ENTITY_RESISTANCES.get(id.getPath());
                for (String dmg : damageTypes) modifierMap.put(dmg, (defaults != null) ? defaults.getOrDefault(dmg, 0f) : 0f);
                EntityResistance er = new EntityResistance();
                er.displayName = id.getPath();
                er.translationKey = type.getDescriptionId();
                er.modifiers = modifierMap;
                er.baseToughness = 0f;
                er.immuneImpactEffects = new ArrayList<>();
                er.impactEffectRemap = new LinkedHashMap<>();
                RESISTANCE_CACHE.put(id, er);
                logMigration("entity_resistances: 新增实体 " + id);
                needUpdate = true;
            }

            if (needUpdate) {
                saveResistanceConfig();
                DamageOverhaulMod.LOGGER.info("Resistance config synced.");
            }
            DamageOverhaulMod.LOGGER.info("Loaded {} entity resistances.", RESISTANCE_CACHE.size());
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load resistance config", e);
        }
    }

    private static void generateDefaultResistanceConfig() {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            Map<String, Float> modifierMap = new HashMap<>();
            Map<String, Float> defaults = DEFAULT_ENTITY_RESISTANCES.get(id.getPath());
            for (String dmg : damageTypes) modifierMap.put(dmg, (defaults != null) ? defaults.getOrDefault(dmg, 0f) : 0f);
            EntityResistance er = new EntityResistance();
            er.displayName = id.getPath();
            er.translationKey = type.getDescriptionId();
            er.modifiers = modifierMap;
            er.baseToughness = 0f;
            er.immuneImpactEffects = new ArrayList<>();
            er.impactEffectRemap = new LinkedHashMap<>();
            RESISTANCE_CACHE.put(id, er);
        }
        saveResistanceConfig();
    }

    private static void saveResistanceConfig() {
        JsonObject meta = new JsonObject();
        meta.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        JsonObject entityMap = new JsonObject();
        for (Map.Entry<ResourceLocation, EntityResistance> entry : RESISTANCE_CACHE.entrySet()) {
            ResourceLocation id = entry.getKey();
            EntityResistance er = entry.getValue();
            JsonObject entityData = new JsonObject();
            entityData.addProperty("display_name", er.displayName);
            entityData.addProperty("translation_key", er.translationKey);
            JsonObject modifiers = new JsonObject();
            for (String type : damageTypes) modifiers.addProperty(type, er.modifiers.getOrDefault(type, 0f));
            entityData.add("modifiers", modifiers);
            entityData.addProperty("base_toughness", er.baseToughness);
            JsonArray immune = new JsonArray();
            for (String s : er.immuneImpactEffects) immune.add(s);
            entityData.add("immune_impact_effects", immune);
            JsonObject remap = new JsonObject();
            for (Map.Entry<String, String> e : er.impactEffectRemap.entrySet()) remap.addProperty(e.getKey(), e.getValue());
            entityData.add("impact_effect_remap", remap);
            entityMap.add(id.toString(), entityData);
        }
        writeFolderConfig(ENTITY_RESISTANCES_DIR, meta, entityMap, "entity_resistances", "entity_resistances");
    }

    // ======================== 武器伤害分布配置（文件夹化） ========================
    private static void loadOrCreateWeaponConfig() {
        try {
            // 旧单文件迁移到文件夹结构
            migrateLegacyFileToFolder(WEAPON_DISTRIBUTIONS_LEGACY, WEAPON_DISTRIBUTIONS_DIR, "weapon_distributions", "weapon_distributions");

            JsonObject[] result = readFolderConfig(WEAPON_DISTRIBUTIONS_DIR, "weapon_distributions", "weapon_distributions");
            JsonObject meta = result[0];
            JsonObject weapons = result[1];

            WEAPON_CACHE.clear();
            boolean needUpdate = false;
            boolean firstCreate = (meta.size() == 0 && weapons.size() == 0);
            if (firstCreate) {
                generateDefaultWeaponConfig();
                DamageOverhaulMod.LOGGER.info("Generated default weapon config folder: {}", WEAPON_DISTRIBUTIONS_DIR);
                return;
            }
            if (checkSchemaVersion(meta, "weapon_distributions")) needUpdate = true;

            // 解析自动学习开关（存放在 _meta.json，缺失时补默认并回写）
            if (meta.has("auto_learn_ranged_weapons")) {
                autoLearnRangedWeapons = meta.get("auto_learn_ranged_weapons").getAsBoolean();
            } else {
                autoLearnRangedWeapons = true;
                needUpdate = true;
            }

            if (weapons != null) {
                for (Map.Entry<String, JsonElement> entry : weapons.entrySet()) {
                    ResourceLocation id = new ResourceLocation(entry.getKey());
                    JsonObject data = entry.getValue().getAsJsonObject();
                    String displayName = data.has("display_name") ? data.get("display_name").getAsString() : id.getPath();
                    JsonObject dist = data.getAsJsonObject("damage_distribution");
                    if (dist == null) { dist = new JsonObject(); }

                    Set<String> toRemove = new HashSet<>();
                    for (String key : dist.keySet()) if (!damageTypes.contains(key)) toRemove.add(key);
                    for (String key : toRemove) {
                        logMigration("weapon_distributions: 武器 " + id + " 移除已删除的伤害类型 '" + key + "'");
                        needUpdate = true;
                    }
                    for (String type : damageTypes) if (!dist.has(type)) needUpdate = true;

                    Map<String, Float> distMap = new HashMap<>();
                    for (String type : damageTypes) distMap.put(type, dist.has(type) ? dist.get(type).getAsFloat() : 0f);
                    WeaponDistribution wd = new WeaponDistribution();
                    wd.displayName = displayName;
                    wd.damageDistribution = distMap;
                    WEAPON_CACHE.put(id, wd);
                }
            }

            // 检查新增武器
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) continue;
                Map<String, Float> defaultDist = getDefaultWeaponDistribution(item);
                if (defaultDist == null || WEAPON_CACHE.containsKey(id)) continue;
                Map<String, Float> distMap = new HashMap<>();
                for (String type : damageTypes) distMap.put(type, defaultDist.getOrDefault(type, 0f));
                WeaponDistribution wd = new WeaponDistribution();
                wd.displayName = id.getPath();
                wd.damageDistribution = distMap;
                WEAPON_CACHE.put(id, wd);
                logMigration("weapon_distributions: 新增武器 " + id);
                needUpdate = true;
            }

            if (needUpdate) {
                saveWeaponConfig();
                DamageOverhaulMod.LOGGER.info("Weapon config synced.");
            }
            DamageOverhaulMod.LOGGER.info("Loaded {} weapon distributions.", WEAPON_CACHE.size());
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load weapon config", e);
        }
    }

    private static void generateDefaultWeaponConfig() {
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            Map<String, Float> defaultDist = getDefaultWeaponDistribution(item);
            if (defaultDist == null) continue;
            Map<String, Float> distMap = new HashMap<>();
            for (String type : damageTypes) distMap.put(type, defaultDist.getOrDefault(type, 0f));
            WeaponDistribution wd = new WeaponDistribution();
            wd.displayName = id.getPath();
            wd.damageDistribution = distMap;
            WEAPON_CACHE.put(id, wd);
        }
        saveWeaponConfig();
    }

    private static void saveWeaponConfig() {
        JsonObject meta = new JsonObject();
        meta.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        meta.addProperty("auto_learn_ranged_weapons", autoLearnRangedWeapons);
        JsonObject weaponMap = new JsonObject();
        for (Map.Entry<ResourceLocation, WeaponDistribution> entry : WEAPON_CACHE.entrySet()) {
            ResourceLocation id = entry.getKey();
            WeaponDistribution wd = entry.getValue();
            JsonObject weaponData = new JsonObject();
            weaponData.addProperty("display_name", wd.displayName);
            JsonObject dist = new JsonObject();
            for (String type : damageTypes) dist.addProperty(type, wd.damageDistribution.getOrDefault(type, 0f));
            weaponData.add("damage_distribution", dist);
            weaponMap.add(id.toString(), weaponData);
        }
        writeFolderConfig(WEAPON_DISTRIBUTIONS_DIR, meta, weaponMap, "weapon_distributions", "weapon_distributions");
    }

    // ======================== 方块源性伤害分布配置 ========================
    private static void loadOrCreateBlockDistConfig() {
        try {
            if (!Files.exists(BLOCK_DIST_FILE)) {
                saveBlockDistConfig();
                DamageOverhaulMod.LOGGER.info("Generated default block distribution config: {}", BLOCK_DIST_FILE);
                return;
            }
            try (Reader reader = new InputStreamReader(Files.newInputStream(BLOCK_DIST_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                BLOCK_DIST_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "block_distributions.json")) needUpdate = true;

                // 自动学习开关（缺失时补默认并回写）
                if (root.has("auto_learn_block_damage")) {
                    autoLearnBlockDamage = root.get("auto_learn_block_damage").getAsBoolean();
                } else {
                    autoLearnBlockDamage = true;
                    needUpdate = true;
                }

                if (root.has("block_distributions") && root.get("block_distributions").isJsonObject()) {
                    JsonObject blocks = root.getAsJsonObject("block_distributions");
                    for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
                        try {
                            ResourceLocation blockId = new ResourceLocation(entry.getKey());
                            JsonObject data = entry.getValue().getAsJsonObject();
                            BlockDistEntry bd = new BlockDistEntry();
                            bd.useDefault = !data.has("use_default") || data.get("use_default").getAsBoolean();
                            if (data.has("damage_distribution") && data.get("damage_distribution").isJsonObject()) {
                                JsonObject dist = data.getAsJsonObject("damage_distribution");
                                for (String type : damageTypes) {
                                    bd.damageDistribution.put(type, dist.has(type) ? dist.get(type).getAsFloat() : 0f);
                                }
                            } else {
                                for (String type : damageTypes) bd.damageDistribution.put(type, 0f);
                            }
                            BLOCK_DIST_CACHE.put(blockId, bd);
                        } catch (Exception ignored) {}
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(BLOCK_DIST_FILE, "block distribution migration");
                    saveBlockDistConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded {} block distributions.", BLOCK_DIST_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load block distribution config", e);
        }
    }

    private static void saveBlockDistConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("auto_learn_block_damage", autoLearnBlockDamage);
        JsonObject blocks = new JsonObject();
        for (Map.Entry<ResourceLocation, BlockDistEntry> entry : BLOCK_DIST_CACHE.entrySet()) {
            JsonObject data = new JsonObject();
            data.addProperty("use_default", entry.getValue().useDefault);
            JsonObject dist = new JsonObject();
            for (String type : damageTypes) {
                dist.addProperty(type, entry.getValue().damageDistribution.getOrDefault(type, 0f));
            }
            data.add("damage_distribution", dist);
            blocks.add(entry.getKey().toString(), data);
        }
        root.add("block_distributions", blocks);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(BLOCK_DIST_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save block distribution config", e);
        }
    }

    /**
     * 自动学习：为造成方块源性伤害的方块添加 use_default=true 条目并写入配置文件。
     * 注册表无法泛化判断"哪些方块会伤害实体"（伤害逻辑在类方法内部，如 stepOn/entityInside），
     * 因此采用运行时数据驱动学习：只记录实际造成过伤害的方块，零硬编码零误报。
     * 用户将该方块条目的 use_default 改为 false 并填写分布后即可接管该方块的伤害。
     */
    public static boolean addLearnedBlock(ResourceLocation blockId) {
        if (blockId == null || BLOCK_DIST_CACHE.containsKey(blockId)) return false;
        BlockDistEntry bd = new BlockDistEntry();
        bd.useDefault = true;
        for (String type : damageTypes) bd.damageDistribution.put(type, 0f);
        BLOCK_DIST_CACHE.put(blockId, bd);
        logMigration("block_distributions.json: 自动学习方块源性伤害方块 " + blockId
                + "（use_default=true；改为 false 并填写 damage_distribution 即可接管）");
        backupBeforeSave(BLOCK_DIST_FILE, "auto learn block damage");
        saveBlockDistConfig();
        DamageOverhaulMod.LOGGER.info("[BlockDist] 自动学习方块源性伤害方块: {}（use_default=true）", blockId);
        return true;
    }

    public static BlockDistEntry getBlockDistEntry(ResourceLocation blockId) {
        return BLOCK_DIST_CACHE.getOrDefault(blockId, null);
    }

    public static boolean isAutoLearnBlockDamage() {
        return autoLearnBlockDamage;
    }

    private static Map<String, Float> getDefaultWeaponDistribution(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return null;

        boolean isTraditionalWeapon = item instanceof SwordItem || item instanceof AxeItem ||
                item instanceof BowItem || item instanceof CrossbowItem ||
                item instanceof TridentItem || item instanceof PickaxeItem ||
                item instanceof ShovelItem || item instanceof HoeItem;

        // 非传统武器类：检测是否有 ATTACK_DAMAGE 属性（能造成伤害的物品）
        if (!isTraditionalWeapon) {
            float attackDamage = getItemAttackDamage(item);
            if (attackDamage <= 0) return null;  // 无攻击伤害属性 → 不是武器
            // 有攻击伤害但非传统武器类 → 默认钝击
            Map<String, Float> dist = new HashMap<>();
            dist.put("blunt", 1.0f);
            return dist;
        }

        boolean isVanilla = "minecraft".equals(id.getNamespace());

        // 模组武器默认全部造成打击（钝击）伤害
        if (!isVanilla) {
            Map<String, Float> dist = new HashMap<>();
            dist.put("blunt", 1.0f);
            return dist;
        }

        // 原版武器按类型分配
        Map<String, Float> dist = new HashMap<>();
        if (item instanceof SwordItem) {
            dist.put("slashing", 0.7f);
            dist.put("piercing", 0.3f);
        } else if (item instanceof AxeItem) {
            dist.put("blunt", 1.0f);
        } else if (item instanceof BowItem || item instanceof CrossbowItem) {
            dist.put("piercing", 1.0f);
        } else if (item instanceof TridentItem) {
            dist.put("piercing", 1.0f);
        } else if (item instanceof PickaxeItem) {
            dist.put("piercing", 0.5f);
            dist.put("blunt", 0.5f);
        } else if (item instanceof ShovelItem) {
            dist.put("blunt", 1.0f);
        } else if (item instanceof HoeItem) {
            dist.put("slashing", 1.0f);
        }
        return dist;
    }

    /** 获取物品的攻击伤害属性值（仅 ATTACK_DAMAGE 修饰符的值，不含玩家基础 1.0） */
    private static float getItemAttackDamage(Item item) {
        ItemStack stack = new ItemStack(item);
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        float damage = 0;
        for (AttributeModifier mod : mods.get(Attributes.ATTACK_DAMAGE)) {
            damage += (float) mod.getAmount();
        }
        return damage;
    }

    // ======================== 空手攻击分布配置 ========================
    private static void loadOrCreateUnarmedConfig() {
        try {
            if (!Files.exists(UNARMED_FILE)) {
                generateDefaultUnarmedConfig();
                DamageOverhaulMod.LOGGER.info("Generated default unarmed config: {}", UNARMED_FILE);
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(UNARMED_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject unarmed = root.getAsJsonObject("unarmed_distributions");

                UNARMED_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "unarmed_distributions.json")) needUpdate = true;

                if (unarmed != null) {
                    for (Map.Entry<String, JsonElement> entry : unarmed.entrySet()) {
                        ResourceLocation id = new ResourceLocation(entry.getKey());
                        JsonObject data = entry.getValue().getAsJsonObject();
                        String displayName = data.has("display_name") ? data.get("display_name").getAsString() : id.getPath();
                        JsonObject dist = data.getAsJsonObject("damage_distribution");
                        if (dist == null) { dist = new JsonObject(); data.add("damage_distribution", dist); }

                        // 同步：移除已删除的伤害类型
                        Set<String> toRemove = new HashSet<>();
                        for (String key : dist.keySet()) if (!damageTypes.contains(key)) toRemove.add(key);
                        for (String key : toRemove) {
                            dist.remove(key);
                            logMigration("unarmed_distributions.json: 实体 " + id + " 移除已删除的伤害类型 '" + key + "'");
                            needUpdate = true;
                        }
                        // 同步：补齐新增的伤害类型
                        for (String type : damageTypes) if (!dist.has(type)) { dist.addProperty(type, 0f); needUpdate = true; }

                        Map<String, Float> distMap = new HashMap<>();
                        for (String type : damageTypes) distMap.put(type, dist.get(type).getAsFloat());
                        WeaponDistribution wd = new WeaponDistribution();
                        wd.displayName = displayName;
                        wd.damageDistribution = distMap;
                        wd.guardBreak = data.has("guard_break") ? data.get("guard_break").getAsFloat() : 0f;
                        wd.impactEffect = data.has("impact_effect") && !data.get("impact_effect").isJsonNull()
                                ? data.get("impact_effect").getAsString() : null;
                        if (!data.has("guard_break")) needUpdate = true;
                        if (!data.has("impact_effect")) needUpdate = true;
                        UNARMED_CACHE.put(id, wd);
                    }
                }

                // 检查新增生物
                for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    if (id == null || UNARMED_CACHE.containsKey(id)) continue;
                    Map<String, Float> defaults = DEFAULT_UNARMED_DISTRIBUTIONS.get(id.getPath());
                    Map<String, Float> distMap = new HashMap<>();
                    for (String dmg : damageTypes) distMap.put(dmg, (defaults != null) ? defaults.getOrDefault(dmg, "blunt".equals(dmg) ? 1f : 0f) : ("blunt".equals(dmg) ? 1f : 0f));
                    WeaponDistribution wd = new WeaponDistribution();
                    wd.displayName = id.getPath();
                    wd.damageDistribution = distMap;
                    UNARMED_CACHE.put(id, wd);
                    logMigration("unarmed_distributions.json: 新增实体 " + id);
                    needUpdate = true;
                }

                if (needUpdate) {
                    backupBeforeSave(UNARMED_FILE, "unarmed config migration");
                    saveUnarmedConfig();
                    DamageOverhaulMod.LOGGER.info("Unarmed config synced.");
                }
                DamageOverhaulMod.LOGGER.info("Loaded {} unarmed distributions.", UNARMED_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load unarmed config", e);
        }
    }

    private static void generateDefaultUnarmedConfig() {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            Map<String, Float> defaults = DEFAULT_UNARMED_DISTRIBUTIONS.get(id.getPath());
            Map<String, Float> distMap = new HashMap<>();
            for (String dmg : damageTypes) distMap.put(dmg, (defaults != null) ? defaults.getOrDefault(dmg, "blunt".equals(dmg) ? 1f : 0f) : ("blunt".equals(dmg) ? 1f : 0f));
            WeaponDistribution wd = new WeaponDistribution();
            wd.displayName = id.getPath();
            wd.damageDistribution = distMap;
            UNARMED_CACHE.put(id, wd);
        }
        saveUnarmedConfig();
    }

    private static void saveUnarmedConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        JsonObject unarmedMap = new JsonObject();
        for (Map.Entry<ResourceLocation, WeaponDistribution> entry : UNARMED_CACHE.entrySet()) {
            ResourceLocation id = entry.getKey();
            WeaponDistribution wd = entry.getValue();
            JsonObject data = new JsonObject();
            data.addProperty("display_name", wd.displayName);
            JsonObject dist = new JsonObject();
            for (String type : damageTypes) dist.addProperty(type, wd.damageDistribution.getOrDefault(type, 0f));
            data.add("damage_distribution", dist);
            data.addProperty("guard_break", wd.guardBreak);
            if (wd.impactEffect != null) data.addProperty("impact_effect", wd.impactEffect);
            unarmedMap.add(id.toString(), data);
        }
        root.add("unarmed_distributions", unarmedMap);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(UNARMED_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save unarmed config", e);
        }
    }

    // ======================== 护甲抗性配置 ========================
    private static void loadOrCreateArmorConfig() {
        try {
            if (!Files.exists(ARMOR_FILE)) {
                generateDefaultArmorConfig();
                DamageOverhaulMod.LOGGER.info("Generated default armor config: {}", ARMOR_FILE);
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(ARMOR_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject armors = root.getAsJsonObject("armor_resistances");

                ARMOR_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "armor_resistances.json")) needUpdate = true;

                if (armors != null) {
                    for (Map.Entry<String, JsonElement> entry : armors.entrySet()) {
                        ResourceLocation id = new ResourceLocation(entry.getKey());
                        JsonObject data = entry.getValue().getAsJsonObject();
                        String displayName = data.has("display_name") ? data.get("display_name").getAsString() : id.getPath();
                        JsonObject res = data.getAsJsonObject("resistances");
                        if (res == null) { res = new JsonObject(); data.add("resistances", res); }

                        // 同步：移除已删除的伤害类型
                        Set<String> toRemove = new HashSet<>();
                        for (String key : res.keySet()) if (!damageTypes.contains(key)) toRemove.add(key);
                        for (String key : toRemove) {
                            res.remove(key);
                            logMigration("armor_resistances.json: 护甲 " + id + " 移除已删除的伤害类型 '" + key + "'");
                            needUpdate = true;
                        }
                        // 同步：补齐新增的伤害类型
                        for (String type : damageTypes) if (!res.has(type)) { res.addProperty(type, 0f); needUpdate = true; }

                        Map<String, Float> resMap = new HashMap<>();
                        for (String type : damageTypes) resMap.put(type, res.get(type).getAsFloat());
                        ArmorResistance ar = new ArmorResistance();
                        ar.displayName = displayName;
                        ar.resistances = resMap;
                        ARMOR_CACHE.put(id, ar);
                    }
                }

                // 检查新增护甲
                for (Item item : BuiltInRegistries.ITEM) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    if (id == null || !(item instanceof ArmorItem) || ARMOR_CACHE.containsKey(id)) continue;
                    Map<String, Float> defaultRes = getDefaultArmorResistance(item, id);
                    if (defaultRes == null) continue;
                    Map<String, Float> resMap = new HashMap<>();
                    for (String type : damageTypes) resMap.put(type, defaultRes.getOrDefault(type, 0f));
                    ArmorResistance ar = new ArmorResistance();
                    ar.displayName = id.getPath();
                    ar.resistances = resMap;
                    ARMOR_CACHE.put(id, ar);
                    logMigration("armor_resistances.json: 新增护甲 " + id);
                    needUpdate = true;
                }

                if (needUpdate) {
                    backupBeforeSave(ARMOR_FILE, "armor config migration");
                    saveArmorConfig();
                    DamageOverhaulMod.LOGGER.info("Armor config synced.");
                }
                DamageOverhaulMod.LOGGER.info("Loaded {} armor resistances.", ARMOR_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load armor config", e);
        }
    }

    private static void generateDefaultArmorConfig() {
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || !(item instanceof ArmorItem)) continue;
            Map<String, Float> defaultRes = getDefaultArmorResistance(item, id);
            if (defaultRes == null) continue;
            Map<String, Float> resMap = new HashMap<>();
            for (String type : damageTypes) resMap.put(type, defaultRes.getOrDefault(type, 0f));
            ArmorResistance ar = new ArmorResistance();
            ar.displayName = id.getPath();
            ar.resistances = resMap;
            ARMOR_CACHE.put(id, ar);
        }
        saveArmorConfig();
    }

    private static void saveArmorConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        JsonObject armorMap = new JsonObject();
        for (Map.Entry<ResourceLocation, ArmorResistance> entry : ARMOR_CACHE.entrySet()) {
            ResourceLocation id = entry.getKey();
            ArmorResistance ar = entry.getValue();
            JsonObject armorData = new JsonObject();
            armorData.addProperty("display_name", ar.displayName);
            JsonObject res = new JsonObject();
            for (String type : damageTypes) res.addProperty(type, ar.resistances.getOrDefault(type, 0f));
            armorData.add("resistances", res);
            armorMap.add(id.toString(), armorData);
        }
        root.add("armor_resistances", armorMap);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(ARMOR_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save armor config", e);
        }
    }

    private static Map<String, Float> getDefaultArmorResistance(Item item, ResourceLocation id) {
        if (!(item instanceof ArmorItem armor)) return null;

        boolean isVanilla = "minecraft".equals(id.getNamespace());

        // 模组护甲默认全抗性 10%
        if (!isVanilla) {
            Map<String, Float> res = new HashMap<>();
            for (String type : damageTypes) res.put(type, 10f);
            return res;
        }

        // 原版护甲按材质和部位分配
        ArmorMaterial material = armor.getMaterial();
        EquipmentSlot slot = armor.getType().getSlot();

        float slotMultiplier = switch (slot) {
            case HEAD -> 0.4f;
            case CHEST -> 1.0f;
            case LEGS -> 0.8f;
            case FEET -> 0.4f;
            default -> 0f;
        };

        Map<String, Float> res = new HashMap<>();

        if (material instanceof ArmorMaterials vanillaMat) {
            float physicalBase = switch (vanillaMat) {
                case LEATHER -> 5f;
                case CHAIN -> 7f;
                case IRON -> 8f;
                case GOLD -> 6f;
                case DIAMOND -> 10f;
                case NETHERITE -> 12f;
                case TURTLE -> 6f;
                default -> 4f;
            };

            float physVal = physicalBase * slotMultiplier;
            res.put("slashing", physVal);
            res.put("blunt", physVal * 0.8f);
            res.put("piercing", physVal);

            switch (vanillaMat) {
                case LEATHER -> res.put("fire", 5f * slotMultiplier);
                case GOLD -> res.put("magic", 6f * slotMultiplier);
                case NETHERITE -> {
                    res.put("fire", 4f * slotMultiplier);
                    res.put("magic", 2f * slotMultiplier);
                }
                case DIAMOND -> res.put("magic", 2f * slotMultiplier);
                case TURTLE -> res.put("fire", 3f * slotMultiplier);
                default -> {}
            }
        } else {
            // 非原版材质但原版命名空间，默认 10%
            for (String type : damageTypes) res.put(type, 10f);
        }

        return res;
    }

    // ======================== 远程武器配置（文件夹化） ========================
    private static void loadOrCreateRangedConfig() {
        try {
            // 旧单文件迁移到文件夹结构
            migrateLegacyFileToFolder(RANGED_CONFIG_LEGACY, RANGED_CONFIG_DIR, "ranged_config", "projectile_distributions");

            JsonObject[] result = readFolderConfig(RANGED_CONFIG_DIR, "projectile_distributions", "ranged_config");
            JsonObject meta = result[0];
            JsonObject projectiles = result[1];

            boolean needUpdate = false;
            boolean firstCreate = (meta.size() == 0 && projectiles.size() == 0);
            if (firstCreate) {
                rangedDamageSource = "projectile"; // 默认射弹决定
                generateDefaultProjectileConfig();
                DamageOverhaulMod.LOGGER.info("Generated default ranged config folder: {}", RANGED_CONFIG_DIR);
                return;
            }

            if (checkSchemaVersion(meta, "ranged_config")) needUpdate = true;

            // 检查缺失字段（新默认 projectile）
            if (!meta.has("ranged_damage_source")) {
                logMigration("ranged_config: 缺少字段 'ranged_damage_source'，已使用默认值 projectile 并回写");
                needUpdate = true;
            }

            rangedDamageSource = meta.has("ranged_damage_source")
                    ? meta.get("ranged_damage_source").getAsString() : "projectile";
            if (!"weapon".equals(rangedDamageSource) && !"projectile".equals(rangedDamageSource)) {
                rangedDamageSource = "projectile";
                logMigration("ranged_config: ranged_damage_source 值无效，已重置为 'projectile'");
                needUpdate = true;
            }

            PROJECTILE_CACHE.clear();

            if (projectiles != null) {
                for (Map.Entry<String, JsonElement> entry : projectiles.entrySet()) {
                    ResourceLocation id = new ResourceLocation(entry.getKey());
                    JsonObject projObj = entry.getValue().getAsJsonObject();
                    if (projObj == null) { projObj = new JsonObject(); needUpdate = true; }

                    // 读取 follow_default 字段（v3 新增）
                    // 缺少时按：两用物品(武器+射弹)=false，纯射弹=true 规则填充
                    boolean computedDefault = defaultFollowDefaultFor(id);
                    boolean followDefault = computedDefault;
                    if (projObj.has("follow_default")) {
                        followDefault = projObj.get("follow_default").getAsBoolean();
                    } else {
                        logMigration("ranged_config: 射弹 " + id + " 缺少 follow_default 字段，已按规则设为 "
                                + computedDefault + " (" + (computedDefault ? "遵循全局默认" : "拒绝默认=武器来源：两用物品") + ")");
                        needUpdate = true;
                    }

                    // 读取伤害分布
                    JsonObject dist = projObj.has("damage_distribution")
                            ? projObj.getAsJsonObject("damage_distribution") : null;
                    if (dist == null) {
                        // 旧格式：整个对象就是伤害分布（没有 follow_default/damage_distribution 嵌套）
                        // 尝试从 projObj 直接读取伤害类型字段
                        boolean hasOldFormat = false;
                        for (String type : damageTypes) {
                            if (projObj.has(type)) { hasOldFormat = true; break; }
                        }
                        if (hasOldFormat) {
                            dist = projObj;
                            logMigration("ranged_config: 射弹 " + id + " 为旧格式，已迁移为新格式"
                                    + " (follow_default=" + computedDefault + ", "
                                    + (computedDefault ? "遵循全局默认" : "两用物品=武器来源") + ")");
                            needUpdate = true;
                        } else {
                            dist = new JsonObject();
                            needUpdate = true;
                        }
                    }

                    // 同步伤害类型
                    Set<String> toRemove = new HashSet<>();
                    for (String key : dist.keySet()) if (!damageTypes.contains(key)) toRemove.add(key);
                    for (String key : toRemove) {
                        logMigration("ranged_config: 射弹 " + id + " 移除已删除的伤害类型 '" + key + "'");
                        needUpdate = true;
                    }
                    for (String type : damageTypes) if (!dist.has(type)) needUpdate = true;

                    Map<String, Float> distMap = new HashMap<>();
                    for (String type : damageTypes) distMap.put(type, dist.has(type) ? dist.get(type).getAsFloat() : 0f);
                    PROJECTILE_CACHE.put(id, new ProjectileConfig(followDefault, distMap));
                }
            }

            // 补齐默认射弹（按规则决定 follow_default）
            for (Map.Entry<String, Map<String, Float>> e : getDefaultProjectileDistributions().entrySet()) {
                ResourceLocation id = new ResourceLocation(e.getKey());
                if (!PROJECTILE_CACHE.containsKey(id)) {
                    Map<String, Float> distMap = new HashMap<>();
                    for (String type : damageTypes) distMap.put(type, e.getValue().getOrDefault(type, 0f));
                    boolean fd = defaultFollowDefaultFor(id);
                    PROJECTILE_CACHE.put(id, new ProjectileConfig(fd, distMap));
                    logMigration("ranged_config: 新增默认射弹 " + id
                            + " (follow_default=" + fd + ", " + (fd ? "遵循全局默认" : "两用物品→武器来源") + ")");
                    needUpdate = true;
                }
            }

            // 自动扫描注册表中的所有射弹实体（包括模组射弹）
            if (scanRegistryForProjectiles()) needUpdate = true;

            if (needUpdate) {
                saveRangedConfig();
                DamageOverhaulMod.LOGGER.info("Ranged config migrated and saved.");
            }
            DamageOverhaulMod.LOGGER.info("Loaded ranged config: source={}, {} projectiles.", rangedDamageSource, PROJECTILE_CACHE.size());
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load ranged config", e);
        }
    }

    /**
     * 自动扫描注册表中所有射弹实体（Projectile 子类），
     * 将不在缓存中的射弹添加到缓存。返回是否添加了新条目。
     * 需要有效的 Level 才能安全创建实体实例（用于 instanceof 检测）。
     */
    private static boolean scanRegistryForProjectiles() {
        if (scanLevel == null) {
            DamageOverhaulMod.LOGGER.warn("[ConfigMigration] scanLevel 为 null，跳过射弹自动扫描（将在服务器启动后执行）");
            return false;
        }
        boolean added = false;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null || PROJECTILE_CACHE.containsKey(id)) continue;
            if (FAILED_SCAN_IDS.contains(id)) continue; // 本会话中创建失败过的实体不再重试
            try {
                net.minecraft.world.entity.Entity entity = type.create(scanLevel);
                if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
                    Map<String, Float> defaultDist = getDefaultProjectileDistributionForEntity(entity);
                    Map<String, Float> distMap = new HashMap<>();
                    for (String dmg : damageTypes) distMap.put(dmg, defaultDist.getOrDefault(dmg, 0f));
                    boolean fd = defaultFollowDefaultFor(id);
                    PROJECTILE_CACHE.put(id, new ProjectileConfig(fd, distMap));
                    logMigration("ranged_config: 自动发现射弹 " + id
                            + " (follow_default=" + fd + ", " + (fd ? "遵循默认" : "两用→武器")
                            + ", 默认分布: " + defaultDist + ")");
                    added = true;
                }
                if (entity != null) entity.discard();
            } catch (Throwable t) {
                // 必须捕获 Throwable：其他模组实体构造函数可能抛出 Error
                // （如 traveloptics 与 irons_spellbooks 版本不匹配 → NoClassDefFoundError），
                // 仅捕获 Exception 会导致 Error 穿透并使服务器启动崩溃
                FAILED_SCAN_IDS.add(id);
                DamageOverhaulMod.LOGGER.warn("[ConfigMigration] 无法检测实体 {} 是否为射弹，本次启动跳过: {}: {}",
                        id, t.getClass().getName(), t.getMessage());
            }
        }
        return added;
    }

    /** 根据射弹类的继承关系判断默认伤害分布 */
    private static Map<String, Float> getDefaultProjectileDistributionForEntity(net.minecraft.world.entity.Entity entity) {
        Map<String, Float> dist = new HashMap<>();
        if (entity instanceof net.minecraft.world.entity.projectile.Fireball) {
            dist.put("fire", 1.0f);
        } else if (entity instanceof net.minecraft.world.entity.projectile.AbstractArrow) {
            dist.put("piercing", 1.0f);
        } else if (entity instanceof net.minecraft.world.entity.projectile.ThrowableProjectile) {
            dist.put("blunt", 1.0f);
        } else {
            dist.put("piercing", 1.0f);
        }
        return dist;
    }

    private static Map<String, Map<String, Float>> getDefaultProjectileDistributions() {
        Map<String, Map<String, Float>> m = new LinkedHashMap<>();
        m.put("minecraft:arrow", Map.of("piercing", 1.0f));
        m.put("minecraft:spectral_arrow", Map.of("piercing", 1.0f));
        m.put("minecraft:trident", Map.of("piercing", 1.0f));
        m.put("minecraft:snowball", Map.of("blunt", 1.0f));
        m.put("minecraft:egg", Map.of("blunt", 1.0f));
        m.put("minecraft:llama_spit", Map.of("blunt", 1.0f));
        m.put("minecraft:fireball", Map.of("fire", 1.0f));
        m.put("minecraft:small_fireball", Map.of("fire", 1.0f));
        m.put("minecraft:dragon_fireball", Map.of("fire", 0.5f, "magic", 0.5f));
        m.put("minecraft:wither_skull", Map.of("dark", 1.0f));
        m.put("minecraft:shulker_bullet", Map.of("piercing", 1.0f));
        m.put("minecraft:eye_of_ender", Map.of("blunt", 1.0f));
        m.put("minecraft:experience_bottle", Map.of("blunt", 1.0f));
        m.put("minecraft:potion", Map.of("magic", 1.0f));
        return m;
    }

    private static void generateDefaultProjectileConfig() {
        PROJECTILE_CACHE.clear();
        for (Map.Entry<String, Map<String, Float>> e : getDefaultProjectileDistributions().entrySet()) {
            ResourceLocation id = new ResourceLocation(e.getKey());
            Map<String, Float> distMap = new HashMap<>();
            for (String type : damageTypes) distMap.put(type, e.getValue().getOrDefault(type, 0f));
            boolean fd = defaultFollowDefaultFor(id);
            PROJECTILE_CACHE.put(id, new ProjectileConfig(fd, distMap));
        }
        // 自动扫描注册表中的所有射弹实体（包括模组射弹）
        scanRegistryForProjectiles();
        saveRangedConfig();
    }

    private static void saveRangedConfig() {
        JsonObject meta = new JsonObject();
        meta.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        meta.addProperty("ranged_damage_source", rangedDamageSource);
        meta.addProperty("_comment_ranged_damage_source", "全局默认远程伤害来源：'weapon'=使用武器伤害分布, 'projectile'=使用射弹自身伤害分布");
        meta.addProperty("_comment_follow_default", "每个射弹的 follow_default=true 则遵循全局默认, false 则使用与全局相反的来源");
        JsonObject projMap = new JsonObject();
        for (Map.Entry<ResourceLocation, ProjectileConfig> entry : PROJECTILE_CACHE.entrySet()) {
            JsonObject projObj = new JsonObject();
            projObj.addProperty("follow_default", entry.getValue().followDefault);
            JsonObject dist = new JsonObject();
            for (String type : damageTypes) dist.addProperty(type, entry.getValue().damageDistribution.getOrDefault(type, 0f));
            projObj.add("damage_distribution", dist);
            projMap.add(entry.getKey().toString(), projObj);
        }
        writeFolderConfig(RANGED_CONFIG_DIR, meta, projMap, "projectile_distributions", "ranged_config");
    }

    // ======================== 弹开机制配置 ========================
    // 弹开率 = (X / Y) × Z
    //   X = 可配置阈值（默认 1/6），Y = 护甲值 / 伤害值
    //   Y < X 时弹开率恒定为 0；Y = X 时弹开率最大（= Z）
    private static void loadOrCreateMechanicsConfig() {
        try {
            if (!Files.exists(MECHANICS_FILE)) {
                deflectionEnabled = true;
                deflectionX = 1.0f / 6.0f;
                deflectionZ = 0.1f;
                debugLoggingEnabled = false;
                deflectionAffectedTypes.clear();
                deflectionAffectedTypes.add("slashing");
                deflectionAffectedTypes.add("blunt");
                deflectionAffectedTypes.add("piercing");
                saveMechanicsConfig();
                DamageOverhaulMod.LOGGER.info("Generated default deflection config: {}", MECHANICS_FILE);
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(MECHANICS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                boolean needUpdate = false;
                // 检查 schema 版本
                if (checkSchemaVersion(root, "special_mechanics.json")) needUpdate = true;

                // 检查缺失字段
                if (checkFieldExists(root, "enabled", "special_mechanics.json", true)) needUpdate = true;
                if (checkFieldExists(root, "x_value", "special_mechanics.json", "1/6")) needUpdate = true;
                if (checkFieldExists(root, "z_value", "special_mechanics.json", 0.1f)) needUpdate = true;
                if (checkFieldExists(root, "affected_damage_types", "special_mechanics.json", "[]")) needUpdate = true;
                if (checkFieldExists(root, "enable_debug_logging", "special_mechanics.json", false)) needUpdate = true;

                deflectionEnabled = root.has("enabled") ? root.get("enabled").getAsBoolean() : true;
                deflectionX = root.has("x_value") ? root.get("x_value").getAsFloat() : (1.0f / 6.0f);
                deflectionZ = root.has("z_value") ? root.get("z_value").getAsFloat() : 0.1f;
                debugLoggingEnabled = root.has("enable_debug_logging") && root.get("enable_debug_logging").getAsBoolean();

                deflectionAffectedTypes.clear();
                if (root.has("affected_damage_types")) {
                    for (JsonElement e : root.getAsJsonArray("affected_damage_types")) {
                        String type = e.getAsString();
                        if (damageTypes.contains(type)) {
                            deflectionAffectedTypes.add(type);
                        } else {
                            logMigration("special_mechanics.json: affected_damage_types 移除已删除的伤害类型 '" + type + "'");
                            needUpdate = true;
                        }
                    }
                }
                if (deflectionAffectedTypes.isEmpty()) {
                    deflectionAffectedTypes.add("slashing");
                    deflectionAffectedTypes.add("blunt");
                    deflectionAffectedTypes.add("piercing");
                    logMigration("special_mechanics.json: affected_damage_types 为空，已重置为默认值");
                    needUpdate = true;
                }

                if (needUpdate) {
                    backupBeforeSave(MECHANICS_FILE, "mechanics config migration");
                    saveMechanicsConfig();
                    DamageOverhaulMod.LOGGER.info("Mechanics config migrated and saved.");
                }
                DamageOverhaulMod.LOGGER.info("Loaded deflection config: enabled={}, X={}, Z={}, types={}",
                        deflectionEnabled, deflectionX, deflectionZ, deflectionAffectedTypes);
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load deflection config", e);
        }
    }

    private static void saveMechanicsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("enabled", deflectionEnabled);
        root.addProperty("x_value", deflectionX);
        root.addProperty("z_value", deflectionZ);
        root.addProperty("_comment_x", "X: 阈值, 默认 1/6. Y=护甲值/伤害值, Y<X时弹开率为0");
        root.addProperty("_comment_z", "Z: 最大弹开率, 默认 0.1 (10%). Y=X时弹开率=Z, Y越大弹开率越低");
        root.addProperty("_comment_formula", "弹开率 = (X / Y) * Z, 弹开后该伤害类型完全免疫");
        root.addProperty("enable_debug_logging", debugLoggingEnabled);
        root.addProperty("_comment_debug", "开启后输出逐事件调试日志（[ProjectileVariant]/[Ranged]/[DamageCalc]/[Enchant]/[BlockDist]）。仅在排查配置时开启，会显著刷屏");
        JsonArray arr = new JsonArray();
        for (String type : deflectionAffectedTypes) arr.add(type);
        root.add("affected_damage_types", arr);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(MECHANICS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save deflection config", e);
        }
    }

    // ======================== 魔力修复配置 ========================
    private static void loadOrCreateManaRepairConfig() {
        try {
            if (!Files.exists(MANA_REPAIR_FILE)) {
                manaRepairEnabled = false;
                defaultManaPerDurability = 60;
                MANA_REPAIR_CACHE.clear();
                generateDefaultManaRepairConfig();
                DamageOverhaulMod.LOGGER.info("Generated default mana repair config: {}", MANA_REPAIR_FILE);
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(MANA_REPAIR_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                boolean needUpdate = false;
                // 检查 schema 版本
                if (checkSchemaVersion(root, "mana_repair_config.json")) needUpdate = true;

                // 检查缺失字段（这些是后续更新添加的字段，旧配置可能没有）
                if (checkFieldExists(root, "enabled", "mana_repair_config.json", false)) needUpdate = true;
                if (checkFieldExists(root, "default_mana_per_durability", "mana_repair_config.json", 60)) needUpdate = true;
                if (checkFieldExists(root, "static_repair_enabled", "mana_repair_config.json", true)) needUpdate = true;
                if (checkFieldExists(root, "static_repair_interval", "mana_repair_config.json", 40)) needUpdate = true;
                if (checkFieldExists(root, "static_repair_per_tick", "mana_repair_config.json", 1)) needUpdate = true;
                if (checkFieldExists(root, "items", "mana_repair_config.json", "{}")) needUpdate = true;

                manaRepairEnabled = root.has("enabled") ? root.get("enabled").getAsBoolean() : false;
                defaultManaPerDurability = root.has("default_mana_per_durability")
                        ? root.get("default_mana_per_durability").getAsInt() : 60;
                staticRepairEnabled = root.has("static_repair_enabled") ? root.get("static_repair_enabled").getAsBoolean() : true;
                staticRepairInterval = root.has("static_repair_interval") ? root.get("static_repair_interval").getAsInt() : 40;
                staticRepairPerTick = root.has("static_repair_per_tick") ? root.get("static_repair_per_tick").getAsInt() : 1;

                JsonObject items = root.getAsJsonObject("items");
                MANA_REPAIR_CACHE.clear();
                if (items != null) {
                    for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                        JsonObject obj = entry.getValue().getAsJsonObject();
                        boolean enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                        int mana = obj.has("mana_per_durability") ? obj.get("mana_per_durability").getAsInt() : defaultManaPerDurability;
                        MANA_REPAIR_CACHE.put(new ResourceLocation(entry.getKey()), new ManaRepairEntry(enabled, mana));
                    }
                }

                // 自动扫描所有有耐久的物品，补齐缺失条目
                for (var itemEntry : BuiltInRegistries.ITEM.entrySet()) {
                    ResourceLocation id = itemEntry.getKey().location();
                    Item item = itemEntry.getValue();
                    if (item.getMaxDamage(new ItemStack(item)) > 0 && !MANA_REPAIR_CACHE.containsKey(id)) {
                        MANA_REPAIR_CACHE.put(id, new ManaRepairEntry(false, defaultManaPerDurability));
                        logMigration("mana_repair_config.json: 新增物品 " + id);
                        needUpdate = true;
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(MANA_REPAIR_FILE, "mana repair config migration");
                    saveManaRepairConfig();
                    DamageOverhaulMod.LOGGER.info("Mana repair config migrated and saved.");
                }
                DamageOverhaulMod.LOGGER.info("Loaded mana repair config: enabled={}, {} items.", manaRepairEnabled, MANA_REPAIR_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load mana repair config", e);
        }
    }

    private static void generateDefaultManaRepairConfig() {
        MANA_REPAIR_CACHE.clear();
        for (var itemEntry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = itemEntry.getKey().location();
            Item item = itemEntry.getValue();
            ItemStack stack = new ItemStack(item);
            if (item.getMaxDamage(stack) > 0) {
                MANA_REPAIR_CACHE.put(id, new ManaRepairEntry(false, defaultManaPerDurability));
            }
        }
        saveManaRepairConfig();
    }

    private static void saveManaRepairConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("enabled", manaRepairEnabled);
        root.addProperty("default_mana_per_durability", defaultManaPerDurability);
        root.addProperty("static_repair_enabled", staticRepairEnabled);
        root.addProperty("static_repair_interval", staticRepairInterval);
        root.addProperty("static_repair_per_tick", staticRepairPerTick);
        root.addProperty("_comment", "魔力修复耐久：消耗植物魔法魔力回复物品耐久。enabled=总开关, items中enabled=单物品开关, mana_per_durability=每回复1点耐久消耗的魔力。static_repair_enabled=背包静态修复开关, static_repair_interval=修复间隔(tick), static_repair_per_tick=每次修复耐久点数");
        JsonObject items = new JsonObject();
        for (Map.Entry<ResourceLocation, ManaRepairEntry> entry : MANA_REPAIR_CACHE.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", entry.getValue().enabled);
            obj.addProperty("mana_per_durability", entry.getValue().manaPerDurability);
            items.add(entry.getKey().toString(), obj);
        }
        root.add("items", items);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(MANA_REPAIR_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save mana repair config", e);
        }
    }

    // ======================== 饰品配置 ========================

    /** 某类型是否有内置独立手绘贴图（决定默认 frames）。 */
    private static boolean hasBuiltinTexture(String type) { return CHARM_WITH_TEXTURE.contains(type); }

    /** 判断 frames 是否为旧版默认值（4 帧 charm_base_0..3 动画），用于迁移检测。 */
    private static boolean isLegacyDefaultFrames(List<String> frames) {
        if (frames.size() != 4) return false;
        return "charm_base_0".equals(frames.get(0)) && "charm_base_1".equals(frames.get(1))
                && "charm_base_2".equals(frames.get(2)) && "charm_base_3".equals(frames.get(3));
    }

    /** 为指定伤害类型生成默认 frames 列表：有独立贴图则用 ["<type>_charm_0"]（静态），否则 ["charm_base_0"]（静态）。 */
    private static List<String> defaultFramesFor(String type) {
        return Collections.singletonList(hasBuiltinTexture(type) ? type + "_charm_0" : "charm_base_0");
    }

    private static List<Integer> singleOrder() { return Collections.singletonList(0); }

    /**
     * 为每种伤害类型生成默认饰品配置（首次启动时调用）。
     * 有内置贴图的类型（fire/magic/lightning/holy/dark）用独立静态贴图（显示手绘原色）；
     * 其余类型（物理 slashing/blunt/piercing 等）用共享灰白 charm_base_0（显示原色，暂搁置等手绘）。
     */
    private static void generateDefaultCharmEntries(JsonArray charmsArray) {
        for (String type : damageTypes) {
            JsonObject c = new JsonObject();
            c.addProperty("id", type + "_charm");
            c.addProperty("display_name", type + " 宝石");
            c.addProperty("damage_type", type);
            JsonObject fx = new JsonObject();
            fx.addProperty("attack_damage_bonus", 0.15f);
            fx.addProperty("defense_resistance", 0.10f);
            fx.addProperty("attack_damage_flat", 0);
            fx.addProperty("defense_resistance_flat", 0);
            c.add("effects", fx);
            JsonObject tex = new JsonObject();
            JsonArray frames = new JsonArray();
            frames.add(defaultFramesFor(type).get(0));
            tex.add("frames", frames);
            JsonArray order = new JsonArray();
            order.add(0);
            tex.add("frame_order", order);
            tex.addProperty("frame_speed", charmDefaultFrameSpeed);
            c.add("texture", tex);
            charmsArray.add(c);
        }
    }

    private static void loadOrCreateCharmsConfig() {
        try {
            if (!Files.exists(CHARMS_FILE)) {
                // 首次生成：为每种伤害类型自动生成默认饰品（无染色，显示贴图原色）
                charmDefaultFrameSpeed = 8;
                CHARM_CACHE.clear();
                for (String type : damageTypes) {
                    CHARM_CACHE.put(type + "_charm", new CharmConfig(
                            type + "_charm", type + " 宝石", type,
                            0.15f, 0.10f, 0, 0,
                            defaultFramesFor(type), singleOrder(), charmDefaultFrameSpeed, "", 0f));
                }
                saveCharmsConfig();
                DamageOverhaulMod.LOGGER.info("Generated default charms config: {} ({} 个饰品)", CHARMS_FILE, CHARM_CACHE.size());
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(CHARMS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                boolean needUpdate = false;
                if (checkSchemaVersion(root, "charms.json")) needUpdate = true;

                // 迁移：移除旧版颜色控制字段（default_color_mapping / tint）
                if (root.has("default_color_mapping")) {
                    root.remove("default_color_mapping");
                    logMigration("charms.json: 移除旧版颜色控制字段 default_color_mapping（已改为贴图原色显示）");
                    needUpdate = true;
                }
                charmDefaultFrameSpeed = root.has("default_frame_speed") ? root.get("default_frame_speed").getAsInt() : 8;

                // 饰品列表
                CHARM_CACHE.clear();
                JsonArray charms = root.has("charms") ? root.getAsJsonArray("charms") : new JsonArray();
                for (JsonElement e : charms) {
                    JsonObject c = e.getAsJsonObject();
                    String id = c.has("id") ? c.get("id").getAsString() : null;
                    String type = c.has("damage_type") ? c.get("damage_type").getAsString() : null;
                    if (id == null || type == null) continue;

                    JsonObject fx = c.has("effects") ? c.getAsJsonObject("effects") : new JsonObject();
                    float atkBonus = fx.has("attack_damage_bonus") ? fx.get("attack_damage_bonus").getAsFloat() : 0;
                    float defRes = fx.has("defense_resistance") ? fx.get("defense_resistance").getAsFloat() : 0;
                    float atkFlat = fx.has("attack_damage_flat") ? fx.get("attack_damage_flat").getAsFloat() : 0;
                    float defFlat = fx.has("defense_resistance_flat") ? fx.get("defense_resistance_flat").getAsFloat() : 0;
                    float toughness = fx.has("toughness") ? fx.get("toughness").getAsFloat() : 0;
                    // v4 迁移：移除旧的 impact_resistance 字段
                    if (fx.has("impact_resistance")) {
                        logMigration("charms.json: 饰品 '" + id + "' 移除旧版 impact_resistance 字段（抗冲击已改为免疫列表 + 重映射）");
                        needUpdate = true;
                    }

                    JsonObject tex = c.has("texture") ? c.getAsJsonObject("texture") : new JsonObject();
                    List<String> frames = new ArrayList<>();
                    if (tex.has("frames")) {
                        for (JsonElement f : tex.getAsJsonArray("frames")) frames.add(f.getAsString());
                    }
                    // 迁移：仍是旧默认 4 帧 charm_base_0..3 的内置贴图类型饰品，升级为独立静态贴图（用户自定义过 frames 的不动）
                    if (isLegacyDefaultFrames(frames) && hasBuiltinTexture(type)) {
                        frames = defaultFramesFor(type);
                        logMigration("charms.json: 饰品 '" + id + "' 升级为独立手绘贴图 (" + frames.get(0) + ")");
                        needUpdate = true;
                    }
                    if (frames.isEmpty()) {
                        frames = defaultFramesFor(type);
                        needUpdate = true;
                    }
                    List<Integer> order = new ArrayList<>();
                    if (tex.has("frame_order")) {
                        for (JsonElement o : tex.getAsJsonArray("frame_order")) order.add(o.getAsInt());
                    }
                    if (order.isEmpty()) { order.add(0); }
                    int speed = tex.has("frame_speed") ? tex.get("frame_speed").getAsInt() : charmDefaultFrameSpeed;
                    // 迁移：移除旧版 tint 字段（不再染色，显示贴图原色）
                    if (tex.has("tint")) {
                        tex.remove("tint");
                        logMigration("charms.json: 饰品 '" + id + "' 移除旧版 tint 染色字段（改为贴图原色）");
                        needUpdate = true;
                    }

                    CHARM_CACHE.put(id, new CharmConfig(id,
                            c.has("display_name") ? c.get("display_name").getAsString() : id,
                            type, atkBonus, defRes, atkFlat, defFlat,
                            frames, order, speed, "", toughness));
                }

                // 自动为新增伤害类型补一个默认饰品（静态单帧，与 getInitialCharms 的 autoFillMissingCharms 一致）
                for (String t : damageTypes) {
                    String autoId = t + "_charm";
                    if (!CHARM_CACHE.containsKey(autoId)) {
                        CHARM_CACHE.put(autoId, new CharmConfig(
                                autoId, t + " 宝石", t,
                                0.15f, 0.10f, 0, 0,
                                defaultFramesFor(t), singleOrder(), charmDefaultFrameSpeed, "", 0f));
                        logMigration("charms.json: 为新伤害类型 '" + t + "' 自动补全默认饰品 (" + autoId + ")");
                        needUpdate = true;
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(CHARMS_FILE, "charms config migration");
                    saveCharmsConfig();
                    DamageOverhaulMod.LOGGER.info("Charms config migrated and saved.");
                }
                DamageOverhaulMod.LOGGER.info("Loaded charms config: {} 个饰品, 默认帧速={}", CHARM_CACHE.size(), charmDefaultFrameSpeed);
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load charms config", e);
        }
    }

    private static void saveCharmsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "通用饰品框架：每个饰品绑定一种伤害类型，含攻击增伤+防御抗性+贴图。同类多饰品取最高不叠加");
        root.addProperty("default_frame_speed", charmDefaultFrameSpeed);
        JsonArray charms = new JsonArray();
        for (CharmConfig c : CHARM_CACHE.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", c.id);
            obj.addProperty("display_name", c.displayName);
            obj.addProperty("damage_type", c.damageType);
            JsonObject fx = new JsonObject();
            fx.addProperty("attack_damage_bonus", c.attackDamageBonus);
            fx.addProperty("defense_resistance", c.defenseResistance);
            fx.addProperty("attack_damage_flat", c.attackDamageFlat);
            fx.addProperty("defense_resistance_flat", c.defenseResistanceFlat);
            fx.addProperty("toughness", c.toughness);
            obj.add("effects", fx);
            JsonObject tex = new JsonObject();
            JsonArray frames = new JsonArray();
            for (String f : c.textureFrames) frames.add(f);
            tex.add("frames", frames);
            JsonArray order = new JsonArray();
            for (Integer o : c.frameOrder) order.add(o);
            tex.add("frame_order", order);
            tex.addProperty("frame_speed", c.frameSpeed);
            obj.add("texture", tex);
            charms.add(obj);
        }
        root.add("charms", charms);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(CHARMS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save charms config", e);
        }
    }

    // ======================== 查询接口 ========================
    public static EntityResistance getResistance(ResourceLocation entityId) {
        return RESISTANCE_CACHE.getOrDefault(entityId, null);
    }

    /** 返回所有饰品配置（启动注册时用） */
    public static List<CharmConfig> getCharmConfigs() {
        return new ArrayList<>(CHARM_CACHE.values());
    }

    /** 按 ID 查询饰品配置 */
    public static CharmConfig getCharmConfig(String id) {
        return CHARM_CACHE.get(id);
    }

    /** 默认帧速（用于未指定的饰品） */
    public static int getCharmDefaultFrameSpeed() {
        return charmDefaultFrameSpeed;
    }

    // ======================== 第三方饰品配置（external_charms.json） ========================

    /** 旧版首次生成文件时写入的示例条目ID，升级表格格式时自动移除（注册表中不存在该物品） */
    private static final String EXTERNAL_CHARM_EXAMPLE_ID = "example_mod:example_charm";

    private static void loadOrCreateExternalCharmsConfig() {
        try {
            int logSizeBefore = MIGRATION_LOG.size();

            if (!Files.exists(EXTERNAL_CHARMS_FILE)) {
                // 首次生成：功能默认启用，表格为空，随后由注册表扫描自动填充
                externalCharmsEnabled = true;
                externalCharmsAutoScan = true;
                EXTERNAL_CHARM_CACHE.clear();
            } else {
                try (Reader reader = new InputStreamReader(Files.newInputStream(EXTERNAL_CHARMS_FILE), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (checkSchemaVersion(root, "external_charms.json")) { /* 仅记日志，logSize 差值判定 needUpdate */ }

                    externalCharmsEnabled = !root.has("enable_external_charms") || root.get("enable_external_charms").getAsBoolean();
                    externalCharmsAutoScan = !root.has("auto_scan_curios_items") || root.get("auto_scan_curios_items").getAsBoolean();

                    EXTERNAL_CHARM_CACHE.clear();
                    JsonArray charms = root.has("charms") && root.get("charms").isJsonArray()
                            ? root.getAsJsonArray("charms") : new JsonArray();
                    for (JsonElement e : charms) {
                        if (!e.isJsonObject()) continue;
                        JsonObject c = e.getAsJsonObject();
                        if (!c.has("item_id")) continue;
                        String itemId = c.get("item_id").getAsString();
                        if (itemId.isEmpty()) continue;
                        // 表格化迁移：移除旧版示例条目
                        if (EXTERNAL_CHARM_EXAMPLE_ID.equals(itemId)) {
                            logMigration("external_charms.json: 移除旧版示例条目 " + EXTERNAL_CHARM_EXAMPLE_ID
                                    + "（改为自动扫描生成真实饰品表格）");
                            continue;
                        }
                        // 稀疏读取（过滤未定义伤害类型，自动跟随 damage_types.json，不硬编码）
                        Map<String, Float> atk = readDamageTypeFloatMap(c, "attack_bonuses", itemId);
                        Map<String, Float> def = readDamageTypeFloatMap(c, "defense_resistances", itemId);
                        float toughness = c.has("toughness") ? c.get("toughness").getAsFloat() : 0f;
                        // v4 迁移：移除旧的 impact_resistance 字段
                        if (c.has("impact_resistance")) {
                            logMigration("external_charms.json: 物品 '" + itemId + "' 移除旧版 impact_resistance 字段（抗冲击已改为免疫列表 + 重映射）");
                        }
                        if (EXTERNAL_CHARM_CACHE.containsKey(itemId)) {
                            logMigration("external_charms.json: 物品 '" + itemId + "' 存在重复条目，保留第一条");
                            continue;
                        }
                        EXTERNAL_CHARM_CACHE.put(itemId, new ExternalCharmConfig(itemId, atk, def, toughness));
                    }
                }
            }

            boolean needUpdate = MIGRATION_LOG.size() > logSizeBefore;

            // 全类型表格化：为每行补齐所有伤害类型列（缺列补0；新增伤害类型由此自动覆盖全部饰品）
            for (ExternalCharmConfig cfg : EXTERNAL_CHARM_CACHE.values()) {
                if (normalizeFactorRow(cfg.attackBonuses) | normalizeFactorRow(cfg.defenseResistances)) {
                    needUpdate = true;
                }
            }

            // 启动全量扫描：注册表中所有带 curios:* 标签的第三方饰品自动收录（全0行）
            if (externalCharmsEnabled && externalCharmsAutoScan && scanLevel != null) {
                int scanned = scanRegistryForCuriosItems();
                if (scanned > 0) needUpdate = true;
            } else if (scanLevel == null) {
                DamageOverhaulMod.LOGGER.debug("[ExternalCharms] 注册表尚未就绪，跳过启动扫描（服务器启动后补扫）");
            }

            if (!Files.exists(EXTERNAL_CHARMS_FILE) || needUpdate) {
                if (Files.exists(EXTERNAL_CHARMS_FILE)) {
                    backupBeforeSave(EXTERNAL_CHARMS_FILE, "external charms table migration/scan");
                }
                saveExternalCharmsConfig();
                DamageOverhaulMod.LOGGER.info("External charms config saved: {} 条饰品", EXTERNAL_CHARM_CACHE.size());
            }
            DamageOverhaulMod.LOGGER.info("Loaded external charms config: {} 条, 启用={}, 启动扫描={}",
                    EXTERNAL_CHARM_CACHE.size(), externalCharmsEnabled, externalCharmsAutoScan);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load external charms config", e);
        }
    }

    /** 把因子行补齐为包含全部伤害类型（缺列补0，保持 damageTypes 顺序）。返回是否补齐过。 */
    private static boolean normalizeFactorRow(Map<String, Float> row) {
        boolean changed = false;
        // 按 damageTypes 顺序重建，顺便剔除已不存在的类型
        Map<String, Float> ordered = new LinkedHashMap<>();
        for (String type : damageTypes) {
            Float v = row.get(type);
            ordered.put(type, v == null ? 0f : v);
            if (v == null) changed = true;
        }
        if (row.size() != ordered.size()) changed = true;
        row.clear();
        row.putAll(ordered);
        return changed;
    }

    /** 构造全0因子行（自动收录的饰品默认无任何影响） */
    private static Map<String, Float> fullZeroFactorRow() {
        Map<String, Float> row = new LinkedHashMap<>();
        for (String type : damageTypes) row.put(type, 0f);
        return row;
    }

    /**
     * 扫描物品注册表，把所有带 curios:* 标签（可装备进 Curios 槽位）的第三方饰品收录为全0行。
     * Curios 5.x 槽位接受度完全由物品标签驱动（namespace="curios" 的任意标签，含兜底 curio）。
     * 排除自家命名空间（damageoverhaul 的 DynamicCharmItem 由 charms.json 管理）。
     * @return 新收录条目数
     */
    private static int scanRegistryForCuriosItems() {
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || "damageoverhaul".equals(id.getNamespace())) continue;
            if (EXTERNAL_CHARM_CACHE.containsKey(id.toString())) continue;
            boolean curiosTagged;
            try {
                // 1.20.1 ItemStack.getTags() 返回 Stream<TagKey<Item>>，经 location() 取标签ID
                curiosTagged = new ItemStack(item).getTags()
                        .anyMatch(t -> "curios".equals(t.location().getNamespace()));
            } catch (Throwable t) {
                // 个别模组物品构造默认栈可能异常，跳过不影响其他扫描
                curiosTagged = false;
            }
            if (curiosTagged) {
                EXTERNAL_CHARM_CACHE.put(id.toString(),
                        new ExternalCharmConfig(id.toString(), fullZeroFactorRow(), fullZeroFactorRow(), 0f));
                logMigration("external_charms.json: 启动扫描收录第三方饰品 " + id + "（全0纯收录，可在表格中填写因子）");
                added++;
            }
        }
        if (added > 0) {
            DamageOverhaulMod.LOGGER.info("[ExternalCharms] 注册表扫描新收录 {} 个第三方饰品", added);
        }
        return added;
    }

    /** 从 JsonObject 读取字符串列表字段（不存在则返回空列表） */
    private static List<String> readStringList(JsonObject obj, String field) {
        List<String> result = new ArrayList<>();
        if (!obj.has(field) || !obj.get(field).isJsonArray()) return result;
        for (JsonElement e : obj.getAsJsonArray(field)) {
            if (e.isJsonPrimitive()) result.add(e.getAsString());
        }
        return result;
    }

    /** 从 JsonObject 读取字符串 Map 字段（不存在则返回空 Map） */
    private static Map<String, String> readStringMap(JsonObject obj, String field) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!obj.has(field) || !obj.get(field).isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(field).entrySet()) {
            if (e.getValue().isJsonPrimitive()) result.put(e.getKey(), e.getValue().getAsString());
        }
        return result;
    }

    private static Map<String, Float> readDamageTypeFloatMap(JsonObject obj, String field, String itemId) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (!obj.has(field) || !obj.get(field).isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> en : obj.getAsJsonObject(field).entrySet()) {
            String type = en.getKey();
            float v = en.getValue().getAsFloat();
            if (!damageTypes.contains(type)) {
                logMigration("external_charms.json: 物品 '" + itemId + "' 的 " + field
                        + " 引用未定义伤害类型 '" + type + "'，已移除");
                continue;
            }
            result.put(type, v);
        }
        return result;
    }

    private static void saveExternalCharmsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "第三方饰品因子表：启动时自动扫描所有带curios标签的第三方饰品、玩家实际装备时补扫，"
                + "每个饰品一行列出全部伤害类型的因子（0=无影响，不显示tooltip；改成如0.15表示+15%）。"
                + "attack_bonuses=伤害增幅(该类型伤害×(1+值))，defense_resistances=类型抗性(该类型伤害×(1-值))。"
                + "同类多饰品取最高不叠加（与自家饰品合并结算）。新增伤害类型会自动为所有行补0列");
        root.addProperty("enable_external_charms", externalCharmsEnabled);
        root.addProperty("auto_scan_curios_items", externalCharmsAutoScan);
        JsonArray charms = new JsonArray();
        for (ExternalCharmConfig c : EXTERNAL_CHARM_CACHE.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("item_id", c.itemId);
            JsonObject atk = new JsonObject();
            for (Map.Entry<String, Float> e : c.attackBonuses.entrySet()) atk.addProperty(e.getKey(), e.getValue());
            obj.add("attack_bonuses", atk);
            JsonObject def = new JsonObject();
            for (Map.Entry<String, Float> e : c.defenseResistances.entrySet()) def.addProperty(e.getKey(), e.getValue());
            obj.add("defense_resistances", def);
            obj.addProperty("toughness", c.toughness);
            charms.add(obj);
        }
        root.add("charms", charms);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(EXTERNAL_CHARMS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save external charms config", e);
        }
    }

    /** 第三方饰品功能是否启用 */
    public static boolean isExternalCharmsEnabled() {
        return externalCharmsEnabled;
    }

    /** 启动时是否全量扫描注册表中的 Curios 饰品 */
    public static boolean isExternalCharmsAutoScan() {
        return externalCharmsAutoScan;
    }

    /** 按物品ID查询第三方饰品配置（功能禁用时返回 null） */
    public static ExternalCharmConfig getExternalCharmConfig(String itemId) {
        if (!externalCharmsEnabled || itemId == null) return null;
        return EXTERNAL_CHARM_CACHE.get(itemId);
    }

    /** 第三方饰品配置条目数（status 显示用） */
    public static int getExternalCharmCount() {
        return EXTERNAL_CHARM_CACHE.size();
    }

    /**
     * 运行时学习：玩家实际装备到 Curios 槽位、但启动扫描未覆盖的物品（如标签动态生成的模组），
     * 批量收录为全0行并一次性 backup + 保存（避免每件物品各写一次文件）。
     * @return 本次新收录的条目数
     */
    public static int learnExternalCharms(java.util.Collection<String> itemIds) {
        if (!externalCharmsEnabled || itemIds == null || itemIds.isEmpty()) return 0;
        List<String> added = new ArrayList<>();
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isEmpty() || EXTERNAL_CHARM_CACHE.containsKey(itemId)) continue;
            EXTERNAL_CHARM_CACHE.put(itemId,
                    new ExternalCharmConfig(itemId, fullZeroFactorRow(), fullZeroFactorRow(), 0f));
            added.add(itemId);
        }
        if (added.isEmpty()) return 0;
        for (String id : added) {
            logMigration("external_charms.json: 运行时学习到新装备的第三方饰品 " + id + "（全0收录，可在表格中填写因子）");
        }
        backupBeforeSave(EXTERNAL_CHARMS_FILE, "runtime learn external charms");
        saveExternalCharmsConfig();
        DamageOverhaulMod.LOGGER.info("[ExternalCharms] 运行时学习新收录 {} 个第三方饰品: {}", added.size(), added);
        return added.size();
    }

    /** 判断一行因子是否全为0（无实际影响） */
    private static boolean isAllZero(Map<String, Float> row) {
        for (Float v : row.values()) {
            if (v != null && v != 0f) return false;
        }
        return true;
    }

    /**
     * 返回第三方饰品快照（不可变深拷贝），用于服务端→客户端同步。
     * 仅包含至少一个非0因子的条目：全0行不产生任何伤害效果与 tooltip，
     * 不同步可避免整合包饰品数量大时登录包膨胀。
     */
    public static Map<String, ExternalCharmConfig> getExternalCharmCacheSnapshot() {
        Map<String, ExternalCharmConfig> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ExternalCharmConfig> e : EXTERNAL_CHARM_CACHE.entrySet()) {
            ExternalCharmConfig src = e.getValue();
            if (isAllZero(src.attackBonuses) && isAllZero(src.defenseResistances)) continue;
            copy.put(e.getKey(), new ExternalCharmConfig(e.getKey(),
                    new LinkedHashMap<>(src.attackBonuses),
                    new LinkedHashMap<>(src.defenseResistances),
                    src.toughness));
        }
        return Collections.unmodifiableMap(copy);
    }

    // ======================== 药水效果配置（mob_effects_config.json） ========================

    private static void loadOrCreateMobEffectsConfig() {
        try {
            int logSizeBefore = MIGRATION_LOG.size();
            mobEffectDefaults = DEFAULT_MOB_EFFECT_VALUES;
            MOB_EFFECT_CACHE.clear();

            if (!Files.exists(MOB_EFFECTS_FILE)) {
                mobEffectsEnabled = true;
                for (String type : damageTypes) MOB_EFFECT_CACHE.put(type, DEFAULT_MOB_EFFECT_VALUES);
            } else {
                try (Reader reader = new InputStreamReader(Files.newInputStream(MOB_EFFECTS_FILE), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (checkSchemaVersion(root, "mob_effects_config.json")) { /* 仅记日志，logSize 差值判定 needUpdate */ }

                    mobEffectsEnabled = !root.has("enable_mob_effects") || root.get("enable_mob_effects").getAsBoolean();

                    if (root.has("defaults") && root.get("defaults").isJsonObject()) {
                        mobEffectDefaults = readMobEffectValues(root.getAsJsonObject("defaults"), DEFAULT_MOB_EFFECT_VALUES);
                    }

                    JsonObject perType = root.has("per_type") && root.get("per_type").isJsonObject()
                            ? root.getAsJsonObject("per_type") : new JsonObject();
                    for (Map.Entry<String, JsonElement> e : perType.entrySet()) {
                        String type = e.getKey();
                        if (!damageTypes.contains(type)) {
                            logMigration("mob_effects_config.json: per_type 引用未定义伤害类型 '" + type + "'，已移除");
                            continue;
                        }
                        if (!e.getValue().isJsonObject()) continue;
                        MOB_EFFECT_CACHE.put(type, readMobEffectValues(e.getValue().getAsJsonObject(), mobEffectDefaults));
                    }
                }
            }

            boolean needUpdate = MIGRATION_LOG.size() > logSizeBefore;

            // 新伤害类型自动补行（按 defaults 行数值），剔除已在上方过滤的未知类型
            for (String type : damageTypes) {
                if (!MOB_EFFECT_CACHE.containsKey(type)) {
                    MOB_EFFECT_CACHE.put(type, mobEffectDefaults);
                    logMigration("mob_effects_config.json: 为新伤害类型 '" + type + "' 自动补齐效果数值行（按 defaults）");
                    needUpdate = true;
                }
            }
            // 保持行顺序与 damageTypes 一致
            Map<String, MobEffectValues> ordered = new LinkedHashMap<>();
            for (String type : damageTypes) ordered.put(type, MOB_EFFECT_CACHE.get(type));
            MOB_EFFECT_CACHE.clear();
            MOB_EFFECT_CACHE.putAll(ordered);

            if (!Files.exists(MOB_EFFECTS_FILE) || needUpdate) {
                if (Files.exists(MOB_EFFECTS_FILE)) {
                    backupBeforeSave(MOB_EFFECTS_FILE, "mob effects config migration");
                }
                saveMobEffectsConfig();
                DamageOverhaulMod.LOGGER.info("Mob effects config saved: {} 个伤害类型行", MOB_EFFECT_CACHE.size());
            }
            DamageOverhaulMod.LOGGER.info("Loaded mob effects config: {} 行, 启用={}", MOB_EFFECT_CACHE.size(), mobEffectsEnabled);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load mob effects config", e);
        }
    }

    /** 读取一行 4 个每级数值；缺失字段回落到 fallback 对应值 */
    private static MobEffectValues readMobEffectValues(JsonObject obj, MobEffectValues fallback) {
        return new MobEffectValues(
                obj.has("resist_up_per_level") ? obj.get("resist_up_per_level").getAsFloat() : fallback.resistUpPerLevel,
                obj.has("resist_down_per_level") ? obj.get("resist_down_per_level").getAsFloat() : fallback.resistDownPerLevel,
                obj.has("damage_up_per_level") ? obj.get("damage_up_per_level").getAsFloat() : fallback.damageUpPerLevel,
                obj.has("damage_down_per_level") ? obj.get("damage_down_per_level").getAsFloat() : fallback.damageDownPerLevel);
    }

    private static void saveMobEffectsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "伤害类型药水效果数值表：每种伤害类型注册4个效果 resist_/vulnerable_/amplify_/weaken_<type>"
                + "（抗性提升/抗性降低/伤害增幅/伤害削弱），本表控制每级数值（实际=每级值×效果等级）。"
                + "resist_up/down 单位为百分比（5.0=每级±5%抗性，受击端并入附魔抗性加性池）；"
                + "damage_up/down 为乘区小数（0.10=每级±10%伤害，攻击端并入饰品增伤乘区）。"
                + "per_type 可逐类型覆盖；defaults 为新增伤害类型补行时的默认来源。"
                + "新增伤害类型自动补行；删除 damage_types.json 中的类型后对应行自动移除。效果注册需重启，数值热重载");
        root.addProperty("enable_mob_effects", mobEffectsEnabled);
        JsonObject defaults = new JsonObject();
        defaults.addProperty("resist_up_per_level", mobEffectDefaults.resistUpPerLevel);
        defaults.addProperty("resist_down_per_level", mobEffectDefaults.resistDownPerLevel);
        defaults.addProperty("damage_up_per_level", mobEffectDefaults.damageUpPerLevel);
        defaults.addProperty("damage_down_per_level", mobEffectDefaults.damageDownPerLevel);
        root.add("defaults", defaults);
        JsonObject perType = new JsonObject();
        for (Map.Entry<String, MobEffectValues> e : MOB_EFFECT_CACHE.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("resist_up_per_level", e.getValue().resistUpPerLevel);
            row.addProperty("resist_down_per_level", e.getValue().resistDownPerLevel);
            row.addProperty("damage_up_per_level", e.getValue().damageUpPerLevel);
            row.addProperty("damage_down_per_level", e.getValue().damageDownPerLevel);
            perType.add(e.getKey(), row);
        }
        root.add("per_type", perType);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(MOB_EFFECTS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save mob effects config", e);
        }
    }

    /** 药水效果功能是否启用（禁用时结算端跳过全部效果查询） */
    public static boolean isMobEffectsEnabled() {
        return mobEffectsEnabled;
    }

    /** 按伤害类型查询 4 个每级效果数值（功能禁用时返回 null） */
    public static MobEffectValues getMobEffectValues(String type) {
        if (!mobEffectsEnabled || type == null) return null;
        return MOB_EFFECT_CACHE.get(type);
    }

    /** 药水效果配置行数（status 显示用） */
    public static int getMobEffectRowCount() {
        return MOB_EFFECT_CACHE.size();
    }

    /** 返回药水效果数值快照（不可变拷贝），用于服务端→客户端同步 */
    public static Map<String, MobEffectValues> getMobEffectCacheSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(MOB_EFFECT_CACHE));
    }

    /** 附魔等级上限（damage_types.json: max_enchant_level；loadAllConfigs 后为权威值） */
    public static int getMaxEnchantLevel() {
        return maxEnchantLevel;
    }

    // ======================== 装备补正（weapon_scaling/ armor_scaling/ 文件夹） ========================

    private static void loadOrCreateScalingConfigs() {
        // 刷新 more_attributes 数据包能力元数据（reload 时数据包可能刚加载完）
        com.zerochen.damageoverhaul.integration.MoreAttributesBridge.invalidate();
        List<String> bridgeAttrs = com.zerochen.damageoverhaul.integration.MoreAttributesBridge.getAttributeNames();

        loadOneScalingConfig(WEAPON_SCALING_DIR, WEAPON_SCALING_LEGACY, WEAPON_SCALING_CACHE, true,
                "weapon_scaling", "enable_weapon_scaling", bridgeAttrs, true);
        loadOneScalingConfig(ARMOR_SCALING_DIR, ARMOR_SCALING_LEGACY, ARMOR_SCALING_CACHE, false,
                "armor_scaling", "enable_armor_scaling", bridgeAttrs, true);
    }

    /**
     * 加载/生成单个装备补正表（文件夹化）。
     * @param dir 配置文件夹路径（_meta.json + per-modid.json）
     * @param legacyFile 旧单文件路径（仅用于迁移检测）
     * @param isWeapon true=武器表（扫描武器并读 enable_weapon_scaling），false=盔甲表
     * @param canScan 注册表就绪时才扫描（ServerStarting 之后）
     */
    private static void loadOneScalingConfig(Path dir, Path legacyFile, Map<String, ScalingRow> cache, boolean isWeapon,
                                             String configName, String enableKey,
                                             List<String> bridgeAttrs, boolean canScan) {
        try {
            boolean needUpdate = false;
            // 旧单文件迁移到文件夹结构
            migrateLegacyFileToFolder(legacyFile, dir, configName, "items");

            JsonObject[] result = readFolderConfig(dir, "items", configName);
            JsonObject meta = result[0];
            JsonObject itemsJson = result[1];

            boolean firstCreate = (meta.size() == 0 && itemsJson.size() == 0);
            LinkedHashMap<String, Float> thresholds = new LinkedHashMap<>(DEFAULT_GRADE_THRESHOLDS);
            LinkedHashMap<String, Float> abilityThresholds = new LinkedHashMap<>(DEFAULT_ABILITY_GRADE_THRESHOLDS);

            if (firstCreate) {
                cache.clear();
                scalingAttributes = new ArrayList<>(bridgeAttrs);
                if (isWeapon) weaponScalingEnabled = true; else armorScalingEnabled = true;
            } else {
                if (checkSchemaVersion(meta, configName)) needUpdate = true;

                boolean enabled = !meta.has(enableKey) || meta.get(enableKey).getAsBoolean();
                if (isWeapon) weaponScalingEnabled = enabled; else armorScalingEnabled = enabled;

                // base_attributes：以 _meta.json 为准，补充桥接器新发现的能力列（数据包扩展时自动覆盖）
                List<String> attrs = new ArrayList<>();
                if (meta.has("base_attributes") && meta.get("base_attributes").isJsonArray()) {
                    for (JsonElement e : meta.getAsJsonArray("base_attributes")) {
                        String a = e.getAsString();
                        if (!attrs.contains(a)) attrs.add(a);
                    }
                }
                for (String a : bridgeAttrs) {
                    if (!attrs.contains(a)) {
                        attrs.add(a);
                        logMigration(configName + ": base_attributes 自动补入 more_attributes 新能力列 '" + a + "'");
                        needUpdate = true;
                    }
                }
                if (attrs.isEmpty()) attrs = new ArrayList<>(bridgeAttrs);
                scalingAttributes = attrs;

                // grade_thresholds：文件可覆盖默认字母阈值
                if (meta.has("grade_thresholds") && meta.get("grade_thresholds").isJsonObject()) {
                    LinkedHashMap<String, Float> parsed = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> e : meta.getAsJsonObject("grade_thresholds").entrySet()) {
                        parsed.put(e.getKey(), e.getValue().getAsFloat());
                    }
                    if (!parsed.isEmpty()) thresholds = parsed;
                }
                if (isWeapon) weaponGradeThresholds = thresholds; else armorGradeThresholds = thresholds;

                // ability_grade_thresholds：装备固有能力补正字母阈值（力量：S 用）
                if (meta.has("ability_grade_thresholds") && meta.get("ability_grade_thresholds").isJsonObject()) {
                    LinkedHashMap<String, Float> parsed = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> e : meta.getAsJsonObject("ability_grade_thresholds").entrySet()) {
                        parsed.put(e.getKey(), e.getValue().getAsFloat());
                    }
                    if (!parsed.isEmpty()) abilityThresholds = parsed;
                }
                if (isWeapon) weaponAbilityGradeThresholds = abilityThresholds;
                else armorAbilityGradeThresholds = abilityThresholds;

                cache.clear();
                for (Map.Entry<String, JsonElement> e : itemsJson.entrySet()) {
                    String itemId = e.getKey();
                    if (!e.getValue().isJsonObject()) continue;
                    if (cache.containsKey(itemId)) {
                        logMigration(configName + ": 物品 '" + itemId + "' 存在重复条目，保留第一条");
                        continue;
                    }
                    cache.put(itemId, readScalingRow(itemId, e.getValue().getAsJsonObject(), attrs, configName));
                }
            }

            if (firstCreate) {
                if (isWeapon) weaponGradeThresholds = thresholds; else armorGradeThresholds = thresholds;
                if (isWeapon) weaponAbilityGradeThresholds = abilityThresholds;
                else armorAbilityGradeThresholds = abilityThresholds;
            }

            // 稠密化：每行补齐 伤害类型 × 能力 全表（缺列补0，剔除已不存在类型/能力）
            for (ScalingRow row : cache.values()) {
                if (normalizeScalingRow(row, scalingAttributes)) needUpdate = true;
            }

            // 启动全量扫描：为全部武器/护甲生成全0行（纯收录）
            if (canScan && scanLevel != null) {
                int scanned = isWeapon ? scanWeaponItems(cache) : scanArmorItems(cache);
                if (scanned > 0) {
                    logMigration(configName + ": 启动扫描新收录 " + scanned + " 件" + (isWeapon ? "武器" : "盔甲") + "（全0行）");
                    needUpdate = true;
                }
            }

            if (firstCreate || needUpdate) {
                saveScalingConfig(dir, cache, isWeapon);
                DamageOverhaulMod.LOGGER.info("{} saved: {} 件装备, 能力列 {} 个",
                        configName, cache.size(), scalingAttributes.size());
            }
            DamageOverhaulMod.LOGGER.info("Loaded {}: {} 件, 启用={}", configName, cache.size(),
                    isWeapon ? weaponScalingEnabled : armorScalingEnabled);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load scaling config: " + dir, e);
        }
    }

    /** 稀疏读取一件装备的倍率表（过滤未定义伤害类型/能力，自动跟随主配置） */
    private static ScalingRow readScalingRow(String itemId, JsonObject obj, List<String> attrs, String fileName) {
        Map<String, Map<String, Float>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> typeEntry : obj.entrySet()) {
            String type = typeEntry.getKey();
            if (!damageTypes.contains(type)) {
                logMigration(fileName + ": 物品 '" + itemId + "' 引用未定义伤害类型 '" + type + "'，已移除");
                continue;
            }
            if (!typeEntry.getValue().isJsonObject()) continue;
            Map<String, Float> attrMap = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> a : typeEntry.getValue().getAsJsonObject().entrySet()) {
                if (!attrs.contains(a.getKey())) {
                    logMigration(fileName + ": 物品 '" + itemId + "' 的 " + type
                            + " 引用未知能力 '" + a.getKey() + "'，已移除");
                    continue;
                }
                attrMap.put(a.getKey(), a.getValue().getAsFloat());
            }
            result.put(type, attrMap);
        }
        return new ScalingRow(itemId, result);
    }

    /** 稠密化一件装备为 伤害类型×能力 完整表格（保持顺序；剔除未知键）。返回是否有补齐/剔除。 */
    private static boolean normalizeScalingRow(ScalingRow row, List<String> attrs) {
        boolean changed = false;
        Map<String, Map<String, Float>> ordered = new LinkedHashMap<>();
        for (String type : damageTypes) {
            Map<String, Float> src = row.multipliers.get(type);
            Map<String, Float> attrMap = new LinkedHashMap<>();
            for (String a : attrs) {
                Float v = src == null ? null : src.get(a);
                attrMap.put(a, v == null ? 0f : v);
                if (v == null) changed = true;
            }
            ordered.put(type, attrMap);
        }
        if (row.multipliers.size() != ordered.size()) changed = true;
        row.multipliers.clear();
        row.multipliers.putAll(ordered);
        return changed;
    }

    /** 扫描注册表：有攻击伤害属性的物品 + 弓/弩/三叉戟 + 已有武器分布条目的物品（含自动学习的枪械） */
    private static int scanWeaponItems(Map<String, ScalingRow> cache) {
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || cache.containsKey(id.toString())) continue;
            boolean isWeapon = false;
            try {
                isWeapon = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
                        || getItemAttackDamage(item) > 0;
            } catch (Throwable ignored) {
                // 个别模组物品构造/属性查询异常时跳过，不影响整表
            }
            if (!isWeapon && WEAPON_CACHE.containsKey(id)) isWeapon = true;
            if (isWeapon) {
                cache.put(id.toString(), newZeroScalingRow(id.toString()));
                added++;
            }
        }
        return added;
    }

    /** 扫描注册表：所有护甲（ArmorItem） */
    private static int scanArmorItems(Map<String, ScalingRow> cache) {
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || cache.containsKey(id.toString())) continue;
            if (item instanceof ArmorItem) {
                cache.put(id.toString(), newZeroScalingRow(id.toString()));
                added++;
            }
        }
        return added;
    }

    /** 全0完整行（伤害类型×能力） */
    private static ScalingRow newZeroScalingRow(String itemId) {
        Map<String, Map<String, Float>> m = new LinkedHashMap<>();
        for (String type : damageTypes) {
            Map<String, Float> attrMap = new LinkedHashMap<>();
            for (String a : scalingAttributes) attrMap.put(a, 0f);
            m.put(type, attrMap);
        }
        return new ScalingRow(itemId, m);
    }

    private static void saveScalingConfig(Path dir, Map<String, ScalingRow> cache, boolean isWeapon) {
        JsonObject meta = new JsonObject();
        meta.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        String kind = isWeapon ? "武器" : "盔甲";
        meta.addProperty("_comment", kind + "补正倍率表。属性点来源：安装 more_attributes 时取其已投属性点；未安装时由本mod内部属性系统（internal_attributes.json，行为积累自动升级）提供。每件装备列出 伤害类型×基础能力 的倍率，"
                + "补正% = Σ(倍率 × 有效属性点)，有效属性点 = 玩家已投等级 − 该能力baseLevel（可为负；新角色为0，需先升级）。"
                + "倍率示例：0.02 = 每点有效属性 +2% 补正；填0=该能力对此类型无补正；倍率与属性点任一为0时tooltip不显示该补正。"
                + (isWeapon
                    ? "武器补正按伤害类型独立，加性并入攻击乘区：类型伤害×(1+饰品增伤+药水增伤+武器补正)。"
                    : "盔甲补正按伤害类型独立，4件护甲逐件连乘 ∏(1−单件补正)，位于附魔+药水抗性之后、饰品抗性之前。")
                + "字母等级用于tooltip显示（补正>0才显示），阈值可在grade_thresholds自定义。"
                + "装备固有能力评级（如 力量：S）按该能力在全部伤害类型的倍率和评定，阈值见ability_grade_thresholds。"
                + "启动自动扫描全部" + kind + "生成全0行；新增伤害类型/新能力自动补列");
        meta.addProperty(isWeapon ? "enable_weapon_scaling" : "enable_armor_scaling",
                isWeapon ? weaponScalingEnabled : armorScalingEnabled);
        JsonArray attrArr = new JsonArray();
        for (String a : scalingAttributes) attrArr.add(a);
        meta.add("base_attributes", attrArr);
        JsonObject grades = new JsonObject();
        for (Map.Entry<String, Float> e : (isWeapon ? weaponGradeThresholds : armorGradeThresholds).entrySet()) {
            grades.addProperty(e.getKey(), e.getValue());
        }
        meta.add("grade_thresholds", grades);
        JsonObject abilityGrades = new JsonObject();
        for (Map.Entry<String, Float> e : (isWeapon ? weaponAbilityGradeThresholds : armorAbilityGradeThresholds).entrySet()) {
            abilityGrades.addProperty(e.getKey(), e.getValue());
        }
        meta.add("ability_grade_thresholds", abilityGrades);
        JsonObject items = new JsonObject();
        for (ScalingRow row : cache.values()) {
            JsonObject rowObj = new JsonObject();
            for (Map.Entry<String, Map<String, Float>> te : row.multipliers.entrySet()) {
                JsonObject attrObj = new JsonObject();
                for (Map.Entry<String, Float> ae : te.getValue().entrySet()) attrObj.addProperty(ae.getKey(), ae.getValue());
                rowObj.add(te.getKey(), attrObj);
            }
            items.add(row.itemId, rowObj);
        }
        String configName = isWeapon ? "weapon_scaling" : "armor_scaling";
        writeFolderConfig(dir, meta, items, "items", configName);
    }

    // ---- 装备补正 API ----

    public static boolean isWeaponScalingEnabled() { return weaponScalingEnabled; }
    public static boolean isArmorScalingEnabled() { return armorScalingEnabled; }

    public static ScalingRow getWeaponScalingRow(String itemId) {
        return weaponScalingEnabled ? WEAPON_SCALING_CACHE.get(itemId) : null;
    }

    public static ScalingRow getArmorScalingRow(String itemId) {
        return armorScalingEnabled ? ARMOR_SCALING_CACHE.get(itemId) : null;
    }

    public static List<String> getScalingAttributes() {
        return List.copyOf(scalingAttributes);
    }

    /**
     * 计算单件装备对各伤害类型的补正比例（小数，可负）：Σ_attr(倍率 × 有效属性点)。
     * @param points 玩家有效属性点（能力名 → 已投−baseLevel）
     */
    public static Map<String, Float> computeCorrections(ScalingRow row, Map<String, Integer> points) {
        Map<String, Float> result = new LinkedHashMap<>();
        if (row == null || points == null || points.isEmpty()) return result;
        for (Map.Entry<String, Map<String, Float>> te : row.multipliers.entrySet()) {
            float sum = 0f;
            for (Map.Entry<String, Float> ae : te.getValue().entrySet()) {
                float mult = ae.getValue();
                if (mult == 0f) continue;
                int p = points.getOrDefault(ae.getKey(), 0);
                if (p == 0) continue;
                sum += mult * p;
            }
            if (sum != 0f) result.put(te.getKey(), sum);
        }
        return result;
    }

    /**
     * 补正比例 → 字母等级。补正 ≤ 0 返回 null（不显示，负补正仍参与结算）。
     * @param correction 小数（0.3 = +30%）
     */
    public static String gradeFor(double correction, boolean weapon) {
        if (correction <= 0) return null;
        Map<String, Float> thresholds = weapon ? weaponGradeThresholds : armorGradeThresholds;
        for (Map.Entry<String, Float> e : thresholds.entrySet()) {
            if (correction >= e.getValue()) return e.getKey();
        }
        return null;
    }

    /**
     * 一件装备的固有能力评级（黑魂式「力量：S」）：按能力聚合该装备在全部伤害
     * 类型下的倍率（求和），仅返回倍率和 > 0 的能力 → 字母等级，按能力列顺序排列。
     * 与持有者属性点无关。
     */
    public static LinkedHashMap<String, String> getAbilityGrades(ScalingRow row, boolean weapon) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (row == null) return result;
        // 以该行稠密表的第一组能力列为准保持顺序
        List<String> attrOrder = scalingAttributes;
        if (attrOrder.isEmpty()) {
            for (Map<String, Float> m : row.multipliers.values()) { attrOrder = new ArrayList<>(m.keySet()); break; }
        }
        Map<String, Float> sums = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Float>> te : row.multipliers.entrySet()) {
            for (Map.Entry<String, Float> ae : te.getValue().entrySet()) {
                if (ae.getValue() != null && ae.getValue() > 0f) {
                    sums.merge(ae.getKey(), ae.getValue(), Float::sum);
                }
            }
        }
        Map<String, Float> thresholds = weapon ? weaponAbilityGradeThresholds : armorAbilityGradeThresholds;
        for (String attr : attrOrder) {
            Float sum = sums.get(attr);
            if (sum == null || sum <= 0f) continue;
            for (Map.Entry<String, Float> e : thresholds.entrySet()) {
                if (sum >= e.getValue()) {
                    result.put(attr, e.getKey());
                    break;
                }
            }
        }
        return result;
    }

    public static int getWeaponScalingCount() { return WEAPON_SCALING_CACHE.size(); }
    public static int getArmorScalingCount() { return ARMOR_SCALING_CACHE.size(); }

    /**
     * 装备补正快照（用于服务端→客户端同步）：仅含至少一个非0倍率的条目，
     * 条目内剔除0单元/空类型，避免整合包装备量大时登录包膨胀。
     */
    public static Map<String, ScalingRow> getWeaponScalingSnapshot() {
        return sparseScalingSnapshot(WEAPON_SCALING_CACHE);
    }

    public static Map<String, ScalingRow> getArmorScalingSnapshot() {
        return sparseScalingSnapshot(ARMOR_SCALING_CACHE);
    }

    private static Map<String, ScalingRow> sparseScalingSnapshot(Map<String, ScalingRow> src) {
        Map<String, ScalingRow> copy = new LinkedHashMap<>();
        for (ScalingRow row : src.values()) {
            if (row.isAllZero()) continue;
            Map<String, Map<String, Float>> sparse = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Float>> te : row.multipliers.entrySet()) {
                Map<String, Float> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, Float> ae : te.getValue().entrySet()) {
                    if (ae.getValue() != null && ae.getValue() != 0f) attrs.put(ae.getKey(), ae.getValue());
                }
                if (!attrs.isEmpty()) sparse.put(te.getKey(), attrs);
            }
            if (!sparse.isEmpty()) copy.put(row.itemId, new ScalingRow(row.itemId, sparse));
        }
        return Collections.unmodifiableMap(copy);
    }

    // ======================== 冲击力/韧性系统 ========================

    // ---- 武器削韧值（weapon_guard_break.json） ----

    private static void loadOrCreateWeaponGuardBreakConfig() {
        try {
            if (!Files.exists(WEAPON_GUARD_BREAK_FILE)) {
                WEAPON_GUARD_BREAK_CACHE.clear();
                saveWeaponGuardBreakConfig();
                DamageOverhaulMod.LOGGER.info("Generated default weapon_guard_break.json: {} 件武器", WEAPON_GUARD_BREAK_CACHE.size());
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(WEAPON_GUARD_BREAK_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                WEAPON_GUARD_BREAK_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "weapon_guard_break.json")) needUpdate = true;

                if (root.has("items") && root.get("items").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("items").entrySet()) {
                        String itemId = entry.getKey();
                        if (!entry.getValue().isJsonObject()) continue;
                        JsonObject data = entry.getValue().getAsJsonObject();
                        float guardBreak = data.has("guard_break") ? data.get("guard_break").getAsFloat() : 0f;
                        String effect = data.has("impact_effect") && !data.get("impact_effect").isJsonNull()
                                ? data.get("impact_effect").getAsString() : null;
                        if (WEAPON_GUARD_BREAK_CACHE.containsKey(itemId)) {
                            logMigration("weapon_guard_break.json: 物品 '" + itemId + "' 存在重复条目，保留第一条");
                            continue;
                        }
                        WEAPON_GUARD_BREAK_CACHE.put(itemId, new WeaponGuardBreak(itemId, guardBreak, effect));
                    }
                }

                // 自动扫描全部武器生成全0行（复用 scanWeaponItems 的判定逻辑）
                if (scanLevel != null) {
                    int scanned = scanItemsForGuardBreak();
                    if (scanned > 0) {
                        logMigration("weapon_guard_break.json: 启动扫描新收录 " + scanned + " 件武器（全0行）");
                        needUpdate = true;
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(WEAPON_GUARD_BREAK_FILE, "weapon_guard_break migration/scan");
                    saveWeaponGuardBreakConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded weapon_guard_break: {} 件武器", WEAPON_GUARD_BREAK_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load weapon_guard_break config", e);
        }
    }

    /** 扫描注册表：有攻击伤害属性的物品 + 弓/弩/三叉戟 + 已有武器分布条目的物品 */
    private static int scanItemsForGuardBreak() {
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || WEAPON_GUARD_BREAK_CACHE.containsKey(id.toString())) continue;
            boolean isWeapon = false;
            try {
                isWeapon = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
                        || getItemAttackDamage(item) > 0;
            } catch (Throwable ignored) {}
            if (!isWeapon && WEAPON_CACHE.containsKey(id)) isWeapon = true;
            if (isWeapon) {
                WEAPON_GUARD_BREAK_CACHE.put(id.toString(), new WeaponGuardBreak(id.toString(), 0f, null));
                added++;
            }
        }
        return added;
    }

    private static void saveWeaponGuardBreakConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "武器削韧值表。每件武器含 guard_break（削韧值）与 impact_effect（引用 impact_effects.json 的效果ID）。"
                + "当 effectiveGuardBreak = max(0, guard_break - 目标总抗冲击) > 目标总韧性 时触发冲击效果。"
                + "总韧性 = 基础韧性(player_config/mob) + Σ护甲toughness + Σ饰品toughness。启动自动扫描全部武器生成全0行");
        JsonObject items = new JsonObject();
        for (WeaponGuardBreak wgb : WEAPON_GUARD_BREAK_CACHE.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("guard_break", wgb.guardBreak);
            if (wgb.impactEffect != null) obj.addProperty("impact_effect", wgb.impactEffect);
            items.add(wgb.itemId, obj);
        }
        root.add("items", items);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(WEAPON_GUARD_BREAK_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save weapon_guard_break config", e);
        }
    }

    // ---- 护甲韧性与抗冲击（armor_toughness.json） ----

    private static void loadOrCreateArmorToughnessConfig() {
        try {
            if (!Files.exists(ARMOR_TOUGHNESS_FILE)) {
                ARMOR_TOUGHNESS_CACHE.clear();
                saveArmorToughnessConfig();
                DamageOverhaulMod.LOGGER.info("Generated default armor_toughness.json: {} 件护甲", ARMOR_TOUGHNESS_CACHE.size());
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(ARMOR_TOUGHNESS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                ARMOR_TOUGHNESS_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "armor_toughness.json")) needUpdate = true;

                if (root.has("items") && root.get("items").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("items").entrySet()) {
                        String itemId = entry.getKey();
                        if (!entry.getValue().isJsonObject()) continue;
                        JsonObject data = entry.getValue().getAsJsonObject();
                        float toughness = data.has("toughness") ? data.get("toughness").getAsFloat() : 0f;
                        // v4 迁移：移除旧的 impact_resistance 字段
                        if (data.has("impact_resistance")) {
                            logMigration("armor_toughness.json: 物品 '" + itemId + "' 移除旧版 impact_resistance 字段（抗冲击已改为免疫列表 + 重映射）");
                            needUpdate = true;
                        }
                        if (ARMOR_TOUGHNESS_CACHE.containsKey(itemId)) {
                            logMigration("armor_toughness.json: 物品 '" + itemId + "' 存在重复条目，保留第一条");
                            continue;
                        }
                        ARMOR_TOUGHNESS_CACHE.put(itemId, new ArmorToughness(itemId, toughness));
                    }
                }

                // 自动扫描全部 ArmorItem 生成全0行
                if (scanLevel != null) {
                    int scanned = scanItemsForArmorToughness();
                    if (scanned > 0) {
                        logMigration("armor_toughness.json: 启动扫描新收录 " + scanned + " 件护甲（全0行）");
                        needUpdate = true;
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(ARMOR_TOUGHNESS_FILE, "armor_toughness migration/scan");
                    saveArmorToughnessConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded armor_toughness: {} 件护甲", ARMOR_TOUGHNESS_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load armor_toughness config", e);
        }
    }

    /** 扫描注册表：所有护甲（ArmorItem） */
    private static int scanItemsForArmorToughness() {
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || ARMOR_TOUGHNESS_CACHE.containsKey(id.toString())) continue;
            if (item instanceof ArmorItem) {
                ARMOR_TOUGHNESS_CACHE.put(id.toString(), new ArmorToughness(id.toString(), 0f));
                added++;
            }
        }
        return added;
    }

    private static void saveArmorToughnessConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "护甲韧性表。toughness 叠入目标总韧性（4件叠加）。"
                + "启动自动扫描全部 ArmorItem 生成全0行");
        JsonObject items = new JsonObject();
        for (ArmorToughness at : ARMOR_TOUGHNESS_CACHE.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("toughness", at.toughness);
            items.add(at.itemId, obj);
        }
        root.add("items", items);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(ARMOR_TOUGHNESS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save armor_toughness config", e);
        }
    }

    // ---- 玩家基础韧性与抗冲击（player_config.json） ----

    private static void loadOrCreatePlayerConfig() {
        try {
            if (!Files.exists(PLAYER_CONFIG_FILE)) {
                savePlayerConfig();
                DamageOverhaulMod.LOGGER.info("Generated default player_config.json");
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(PLAYER_CONFIG_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "player_config.json")) needUpdate = true;

                playerBaseToughness = root.has("base_toughness") ? root.get("base_toughness").getAsFloat() : 10.0f;

                // v4 迁移：移除旧的 base_impact_resistance 数值字段（已改为免疫列表 + 重映射 Map）
                if (root.has("base_impact_resistance")) {
                    logMigration("player_config.json: 移除旧版 base_impact_resistance 字段（已改为 immune_impact_effects + impact_effect_remap）");
                    needUpdate = true;
                }

                playerImmuneImpactEffects.clear();
                playerImmuneImpactEffects.addAll(readStringList(root, "immune_impact_effects"));
                playerImpactEffectRemap.clear();
                playerImpactEffectRemap.putAll(readStringMap(root, "impact_effect_remap"));

                if (!root.has("immune_impact_effects") || !root.has("impact_effect_remap")) needUpdate = true;

                if (needUpdate) {
                    backupBeforeSave(PLAYER_CONFIG_FILE, "player_config migration");
                    savePlayerConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded player_config: toughness={}, immune={}, remap={}",
                        playerBaseToughness, playerImmuneImpactEffects.size(), playerImpactEffectRemap.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load player_config", e);
        }
    }

    private static void savePlayerConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "玩家基础韧性与抗冲击。总韧性 = base_toughness + Σ护甲toughness + Σ饰品toughness。"
                + "抗冲击：immune_impact_effects 列出完全无视的冲击效果ID；"
                + "impact_effect_remap 将冲击力D重映射为冲击力C（key=原效果ID, value=替换效果ID）。"
                + "削韧值不受抗冲击影响，直接扣除韧性；韧性<=0时触发冲击效果");
        root.addProperty("base_toughness", playerBaseToughness);

        JsonArray immune = new JsonArray();
        for (String s : playerImmuneImpactEffects) immune.add(s);
        root.add("immune_impact_effects", immune);

        JsonObject remap = new JsonObject();
        for (Map.Entry<String, String> e : playerImpactEffectRemap.entrySet()) remap.addProperty(e.getKey(), e.getValue());
        root.add("impact_effect_remap", remap);

        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(PLAYER_CONFIG_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save player_config", e);
        }
    }

    // ---- 冲击效果预设（impact_effects.json） ----

    private static void loadOrCreateImpactEffectsConfig() {
        try {
            if (!Files.exists(IMPACT_EFFECTS_FILE)) {
                IMPACT_EFFECTS_CACHE.clear();
                // 默认效果预设（三轴方向：up_down / left_right / forward_backward，+/- 表示方向）
                IMPACT_EFFECTS_CACHE.put("light_knockback", new ImpactEffect("light_knockback", 0f, 0f, 1f, 2.0f, 20, 10));
                IMPACT_EFFECTS_CACHE.put("heavy_knockback", new ImpactEffect("heavy_knockback", 0f, 0f, 1f, 4.0f, 40, 20));
                IMPACT_EFFECTS_CACHE.put("launch_up", new ImpactEffect("launch_up", 1f, 0f, 0f, 3.0f, 30, 15));
                saveImpactEffectsConfig();
                DamageOverhaulMod.LOGGER.info("Generated default impact_effects.json: {} 个效果", IMPACT_EFFECTS_CACHE.size());
                return;
            }

            try (Reader reader = new InputStreamReader(Files.newInputStream(IMPACT_EFFECTS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                IMPACT_EFFECTS_CACHE.clear();
                boolean needUpdate = false;
                if (checkSchemaVersion(root, "impact_effects.json")) needUpdate = true;

                if (root.has("effects") && root.get("effects").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("effects").entrySet()) {
                        String effectId = entry.getKey();
                        if (!entry.getValue().isJsonObject()) continue;
                        JsonObject data = entry.getValue().getAsJsonObject();

                        float upDown, leftRight, forwardBackward;
                        if (data.has("direction") && !data.has("up_down")) {
                            // 旧格式迁移：direction 字符串 → 三轴数值
                            String dir = data.get("direction").getAsString().toLowerCase();
                            upDown = "up".equals(dir) ? 1f : ("down".equals(dir) ? -1f : 0f);
                            leftRight = "right".equals(dir) ? 1f : ("left".equals(dir) ? -1f : 0f);
                            forwardBackward = "forward".equals(dir) ? 1f : ("backward".equals(dir) ? -1f : 0f);
                            logMigration("impact_effects.json: 效果 '" + effectId + "' 从旧 direction='" + dir + "' 迁移为三轴方向");
                            needUpdate = true;
                        } else {
                            upDown = data.has("up_down") ? data.get("up_down").getAsFloat() : 0f;
                            leftRight = data.has("left_right") ? data.get("left_right").getAsFloat() : 0f;
                            forwardBackward = data.has("forward_backward") ? data.get("forward_backward").getAsFloat() : 0f;
                        }

                        float distance = data.has("distance") ? data.get("distance").getAsFloat() : 2.0f;
                        int durationTick = data.has("duration_tick") ? data.get("duration_tick").getAsInt() : 20;
                        int aiPauseTick = data.has("ai_pause_tick") ? data.get("ai_pause_tick").getAsInt() : 10;
                        IMPACT_EFFECTS_CACHE.put(effectId, new ImpactEffect(effectId, upDown, leftRight, forwardBackward, distance, durationTick, aiPauseTick));
                    }
                }

                if (IMPACT_EFFECTS_CACHE.isEmpty()) {
                    IMPACT_EFFECTS_CACHE.put("light_knockback", new ImpactEffect("light_knockback", 0f, 0f, 1f, 2.0f, 20, 10));
                    needUpdate = true;
                }

                if (needUpdate) {
                    backupBeforeSave(IMPACT_EFFECTS_FILE, "impact_effects migration");
                    saveImpactEffectsConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded impact_effects: {} 个效果", IMPACT_EFFECTS_CACHE.size());
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load impact_effects config", e);
        }
    }

    private static void saveImpactEffectsConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "冲击效果预设表。方向为三轴数值（相对受害者朝向）："
                + "up_down=上下（+上升/-下降），left_right=左右（+右/-左），forward_backward=前后（+前/-后）。"
                + "distance=位移距离；duration_tick=玩家stun/freeze时长(tick)；ai_pause_tick=mob AI暂停时长(tick)。"
                + "武器 guard_break > 目标总韧性时触发武器指定的 impact_effect");
        JsonObject effects = new JsonObject();
        for (ImpactEffect ie : IMPACT_EFFECTS_CACHE.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("up_down", ie.upDown);
            obj.addProperty("left_right", ie.leftRight);
            obj.addProperty("forward_backward", ie.forwardBackward);
            obj.addProperty("distance", ie.distance);
            obj.addProperty("duration_tick", ie.durationTick);
            obj.addProperty("ai_pause_tick", ie.aiPauseTick);
            effects.add(ie.id, obj);
        }
        root.add("effects", effects);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(IMPACT_EFFECTS_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save impact_effects config", e);
        }
    }

    // ---- 冲击力/韧性系统 API ----

    public static WeaponGuardBreak getWeaponGuardBreak(String itemId) {
        return WEAPON_GUARD_BREAK_CACHE.get(itemId);
    }

    public static ArmorToughness getArmorToughness(String itemId) {
        return ARMOR_TOUGHNESS_CACHE.get(itemId);
    }

    public static ImpactEffect getImpactEffect(String effectId) {
        return IMPACT_EFFECTS_CACHE.get(effectId);
    }

    public static float getPlayerBaseToughness() { return playerBaseToughness; }
    public static List<String> getPlayerImmuneImpactEffects() { return Collections.unmodifiableList(playerImmuneImpactEffects); }
    public static Map<String, String> getPlayerImpactEffectRemap() { return Collections.unmodifiableMap(playerImpactEffectRemap); }

    /** 同步给客户端的快照（仅含 guard_break>0 或 impact_effect!=null 的条目） */
    public static Map<String, WeaponGuardBreak> getWeaponGuardBreakSnapshot() {
        Map<String, WeaponGuardBreak> copy = new LinkedHashMap<>();
        for (WeaponGuardBreak wgb : WEAPON_GUARD_BREAK_CACHE.values()) {
            if (wgb.guardBreak > 0 || wgb.impactEffect != null) {
                copy.put(wgb.itemId, new WeaponGuardBreak(wgb.itemId, wgb.guardBreak, wgb.impactEffect));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 同步给客户端的快照（仅含 toughness>0 的条目） */
    public static Map<String, ArmorToughness> getArmorToughnessSnapshot() {
        Map<String, ArmorToughness> copy = new LinkedHashMap<>();
        for (ArmorToughness at : ARMOR_TOUGHNESS_CACHE.values()) {
            if (at.toughness > 0) {
                copy.put(at.itemId, new ArmorToughness(at.itemId, at.toughness));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 同步给客户端的快照（全量，预设少） */
    public static Map<String, ImpactEffect> getImpactEffectsSnapshot() {
        Map<String, ImpactEffect> copy = new LinkedHashMap<>();
        for (ImpactEffect ie : IMPACT_EFFECTS_CACHE.values()) {
            copy.put(ie.id, new ImpactEffect(ie.id, ie.upDown, ie.leftRight, ie.forwardBackward, ie.distance, ie.durationTick, ie.aiPauseTick));
        }
        return Collections.unmodifiableMap(copy);
    }

    // ======================== 内部属性系统（internal_attributes.json） ========================

    /** 内置9能力的默认 [baseLevel, maxLevel]（与 more_attributes 同名同语义，strength base=10） */
    private static void resetInternalAttributesToDefaults() {
        INTERNAL_ATTRIBUTES.clear();
        INTERNAL_ATTRIBUTES.put("strength", new int[]{10, 100});
        INTERNAL_ATTRIBUTES.put("health", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("focus", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("stamina", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("endurance", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("intelligence", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("agility", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("skill", new int[]{0, 100});
        INTERNAL_ATTRIBUTES.put("luck", new int[]{0, 100});
        INTERNAL_XP_SOURCES.clear();
        putXpSource("kill", "strength", 5.0);
        putXpSource("kill", "luck", 1.0);
        putXpSource("deal_damage", "strength", 0.2);
        putXpSource("deal_damage", "focus", 0.1);
        putXpSource("take_damage", "endurance", 0.3);
        putXpSource("take_damage", "health", 0.1);
        putXpSource("mine_block", "stamina", 1.0);
        putXpSource("sprint_second", "agility", 0.5);
        putXpSource("jump", "agility", 1.0);
        putXpSource("craft_item", "skill", 2.0);
        putXpSource("fish", "luck", 5.0);
        putXpSource("trade", "luck", 3.0);
        putXpSource("trade", "skill", 1.0);
        putXpSource("breed_animal", "luck", 2.0);
        putXpSource("eat_food", "health", 1.0);
    }

    private static void putXpSource(String source, String attr, double amount) {
        INTERNAL_XP_SOURCES.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(attr, amount);
    }

    private static void loadOrCreateInternalAttributesConfig() {
        try {
            boolean needUpdate = false;
            boolean firstCreate = !Files.exists(INTERNAL_ATTRIBUTES_FILE);

            if (firstCreate) {
                internalAttributesEnabled = true;
                internalLevelBaseXp = 100.0;
                internalLevelExponent = 1.5;
                killHealthDivisor = 20.0;
                resetInternalAttributesToDefaults();
            } else {
                try (Reader reader = new InputStreamReader(Files.newInputStream(INTERNAL_ATTRIBUTES_FILE), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (checkSchemaVersion(root, "internal_attributes.json")) needUpdate = true;

                    internalAttributesEnabled = !root.has("enable_internal_attributes")
                            || root.get("enable_internal_attributes").getAsBoolean();

                    if (root.has("level_curve") && root.get("level_curve").isJsonObject()) {
                        JsonObject curve = root.getAsJsonObject("level_curve");
                        if (curve.has("base_xp")) internalLevelBaseXp = Math.max(1.0, curve.get("base_xp").getAsDouble());
                        if (curve.has("exponent")) internalLevelExponent = Math.max(0.1, curve.get("exponent").getAsDouble());
                    }
                    if (root.has("kill_health_divisor")) {
                        killHealthDivisor = Math.max(1.0, root.get("kill_health_divisor").getAsDouble());
                    }

                    INTERNAL_ATTRIBUTES.clear();
                    if (root.has("attributes") && root.get("attributes").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("attributes").entrySet()) {
                            if (!e.getValue().isJsonObject()) continue;
                            JsonObject ao = e.getValue().getAsJsonObject();
                            int base = ao.has("base_level") ? ao.get("base_level").getAsInt() : 0;
                            int max = ao.has("max_level") ? ao.get("max_level").getAsInt() : 100;
                            INTERNAL_ATTRIBUTES.put(e.getKey(), new int[]{Math.max(0, base), Math.max(1, max)});
                        }
                    }
                    // 稠密化：内置9能力必须存在（缺失自动补默认，保证补正表列名稳定）
                    String[][] defaults = {
                            {"strength", "10"}, {"health", "0"}, {"focus", "0"}, {"stamina", "0"},
                            {"endurance", "0"}, {"intelligence", "0"}, {"agility", "0"},
                            {"skill", "0"}, {"luck", "0"}};
                    for (String[] d : defaults) {
                        if (!INTERNAL_ATTRIBUTES.containsKey(d[0])) {
                            INTERNAL_ATTRIBUTES.put(d[0], new int[]{Integer.parseInt(d[1]), 100});
                            logMigration("internal_attributes.json: attributes 自动补入缺失能力 '" + d[0] + "'");
                            needUpdate = true;
                        }
                    }
                    if (INTERNAL_ATTRIBUTES.isEmpty()) {
                        resetInternalAttributesToDefaults();
                        needUpdate = true;
                    }

                    INTERNAL_XP_SOURCES.clear();
                    if (root.has("xp_sources") && root.get("xp_sources").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> se : root.getAsJsonObject("xp_sources").entrySet()) {
                            if (!se.getValue().isJsonObject()) continue;
                            Map<String, Double> amounts = new LinkedHashMap<>();
                            for (Map.Entry<String, JsonElement> ae : se.getValue().getAsJsonObject().entrySet()) {
                                if (!INTERNAL_ATTRIBUTES.containsKey(ae.getKey())) {
                                    logMigration("internal_attributes.json: xp_sources." + se.getKey()
                                            + " 引用未知能力 '" + ae.getKey() + "'，已移除");
                                    needUpdate = true;
                                    continue;
                                }
                                amounts.put(ae.getKey(), ae.getValue().getAsDouble());
                            }
                            if (!amounts.isEmpty()) INTERNAL_XP_SOURCES.put(se.getKey(), amounts);
                        }
                    }
                    if (INTERNAL_XP_SOURCES.isEmpty()) {
                        // 文件存在但 xp_sources 为空/全被剔除：恢复默认行为表，避免"系统开了但不涨经验"
                        INTERNAL_XP_SOURCES.clear();
                        putXpSource("kill", "strength", 5.0);
                        putXpSource("kill", "luck", 1.0);
                        putXpSource("deal_damage", "strength", 0.2);
                        putXpSource("deal_damage", "focus", 0.1);
                        putXpSource("take_damage", "endurance", 0.3);
                        putXpSource("take_damage", "health", 0.1);
                        putXpSource("mine_block", "stamina", 1.0);
                        putXpSource("sprint_second", "agility", 0.5);
                        putXpSource("jump", "agility", 1.0);
                        putXpSource("craft_item", "skill", 2.0);
                        putXpSource("fish", "luck", 5.0);
                        putXpSource("trade", "luck", 3.0);
                        putXpSource("trade", "skill", 1.0);
                        putXpSource("breed_animal", "luck", 2.0);
                        putXpSource("eat_food", "health", 1.0);
                    }
                }
            }

            if (firstCreate || needUpdate) {
                if (!firstCreate) backupBeforeSave(INTERNAL_ATTRIBUTES_FILE, "internal_attributes.json migration");
                saveInternalAttributesConfig();
                DamageOverhaulMod.LOGGER.info("internal_attributes.json saved: {} 能力, {} 经验来源",
                        INTERNAL_ATTRIBUTES.size(), INTERNAL_XP_SOURCES.size());
            }
            DamageOverhaulMod.LOGGER.info("Loaded internal_attributes.json: {} 能力, 启用={}, base_xp={}, exponent={}",
                    INTERNAL_ATTRIBUTES.size(), internalAttributesEnabled, internalLevelBaseXp, internalLevelExponent);
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load internal attributes config: " + INTERNAL_ATTRIBUTES_FILE, e);
            if (INTERNAL_ATTRIBUTES.isEmpty()) resetInternalAttributesToDefaults();
        }
    }

    private static void saveInternalAttributesConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", CONFIG_SCHEMA_VERSION);
        root.addProperty("_comment", "内部属性系统（more_attributes 未安装时启用；已安装时本系统完全休眠，数据保留）。"
                + "玩家通过行为自动获得经验并升级：升级所需经验 = base_xp × (当前有效等级+1)^exponent。"
                + "有效属性点 = 等级 − base_level，供装备补正（weapon_scaling/armor_scaling）作为数据源。"
                + "xp_sources 为行为→能力→每次经验：kill=击杀(按目标最大生命/除数放大) deal_damage=每点伤害 "
                + "take_damage=每点承伤 mine_block=挖掘 sprint_second=每秒疾跑 jump=跳跃 craft_item=合成 "
                + "fish=钓鱼 trade=交易 breed_animal=繁殖 eat_food=进食。可自由增删来源键与能力列。");
        root.addProperty("enable_internal_attributes", internalAttributesEnabled);
        JsonObject curve = new JsonObject();
        curve.addProperty("base_xp", internalLevelBaseXp);
        curve.addProperty("exponent", internalLevelExponent);
        root.add("level_curve", curve);
        root.addProperty("kill_health_divisor", killHealthDivisor);
        JsonObject attrs = new JsonObject();
        for (Map.Entry<String, int[]> e : INTERNAL_ATTRIBUTES.entrySet()) {
            JsonObject ao = new JsonObject();
            ao.addProperty("base_level", e.getValue()[0]);
            ao.addProperty("max_level", e.getValue()[1]);
            attrs.add(e.getKey(), ao);
        }
        root.add("attributes", attrs);
        JsonObject sources = new JsonObject();
        for (Map.Entry<String, Map<String, Double>> se : INTERNAL_XP_SOURCES.entrySet()) {
            JsonObject so = new JsonObject();
            for (Map.Entry<String, Double> ae : se.getValue().entrySet()) so.addProperty(ae.getKey(), ae.getValue());
            sources.add(se.getKey(), so);
        }
        root.add("xp_sources", sources);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(INTERNAL_ATTRIBUTES_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save internal attributes config", e);
        }
    }

    // ---- 内部属性 API ----

    public static boolean isInternalAttributesEnabled() { return internalAttributesEnabled; }

    /** 全部内部能力名（有序） */
    public static List<String> getInternalAttributeNames() {
        return new ArrayList<>(INTERNAL_ATTRIBUTES.keySet());
    }

    /** 能力 baseLevel（未配置返回 0） */
    public static int getInternalBaseLevel(String name) {
        int[] v = INTERNAL_ATTRIBUTES.get(name);
        return v == null ? 0 : v[0];
    }

    /** 能力最大等级（未配置返回 100） */
    public static int getInternalMaxLevel(String name) {
        int[] v = INTERNAL_ATTRIBUTES.get(name);
        return v == null ? 100 : v[1];
    }

    /** 全部能力的 name → baseLevel（MoreAttributesBridge 内部源元数据） */
    public static Map<String, Integer> getInternalBaseLevels() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : INTERNAL_ATTRIBUTES.entrySet()) result.put(e.getKey(), e.getValue()[0]);
        return result;
    }

    public static double getInternalLevelBaseXp() { return internalLevelBaseXp; }
    public static double getInternalLevelExponent() { return internalLevelExponent; }
    public static double getKillHealthDivisor() { return killHealthDivisor; }

    /** 某行为来源的经验表（能力 → 经验量）；未知来源返回空表 */
    public static Map<String, Double> getInternalXpSource(String sourceKey) {
        Map<String, Double> src = INTERNAL_XP_SOURCES.get(sourceKey);
        return src == null ? Collections.emptyMap() : Collections.unmodifiableMap(src);
    }

    /**
     * 从当前等级升到下一级所需经验：base_xp × (当前有效等级+1)^exponent。
     * @param currentLevel 当前等级（含 baseLevel）
     */
    public static double internalXpRequired(String attr, int currentLevel) {
        int effective = Math.max(0, currentLevel - getInternalBaseLevel(attr));
        return internalLevelBaseXp * Math.pow(effective + 1, internalLevelExponent);
    }

    /** 早期预读 damage_types.json（仅当 damageTypes 尚未加载时；loadAllConfigs 之后走缓存）。 */
    private static List<String> getEarlyDamageTypes() {
        if (!damageTypes.isEmpty()) return damageTypes;
        try {
            if (Files.exists(DAMAGE_TYPES_FILE)) {
                try (Reader reader = new InputStreamReader(Files.newInputStream(DAMAGE_TYPES_FILE), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    List<String> list = new ArrayList<>();
                    if (root.has("damage_types") && root.get("damage_types").isJsonArray()) {
                        for (JsonElement e : root.getAsJsonArray("damage_types")) {
                            String name = e.getAsString();
                            if (!name.isEmpty() && !list.contains(name)) list.add(name);
                        }
                    }
                    if (!list.isEmpty()) return list;
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(DEFAULT_DAMAGE_TYPES);
    }

    /**
     * 自动补全：为所有还没有饰品的伤害类型生成默认饰品（<type>_charm，静态单帧 charm_base_0 + 按类型染色）。
     * 新增项追加写入 charms.json（先备份 + 迁移日志），保证 ModItems 物品注册阶段就能拿到全部饰品。
     */
    private static void autoFillMissingCharms() {
        List<String> types = getEarlyDamageTypes();
        boolean added = false;
        for (String t : types) {
            String autoId = t + "_charm";
            if (CHARM_CACHE.containsKey(autoId)) continue;
            // 静态贴图：单帧，无动画（有内置贴图用独立贴图，否则共享 charm_base_0，显示灰白原色）
            CHARM_CACHE.put(autoId, new CharmConfig(
                    autoId, t + " 宝石", t,
                    0.15f, 0.10f, 0, 0,
                    defaultFramesFor(t), singleOrder(), charmDefaultFrameSpeed, "", 0f));
            logMigration("charms.json: 为新伤害类型 '" + t + "' 自动补全默认饰品 (" + autoId + ")");
            added = true;
        }
        if (added) {
            backupBeforeSave(CHARMS_FILE, "charms auto-fill");
            saveCharmsConfig();
            DamageOverhaulMod.LOGGER.info("[CharmAutoFill] 已为缺失伤害类型自动补全饰品，当前共 {} 个", CHARM_CACHE.size());
        }
    }

    /**
     * 启动时早期预读 charms.json，供 ModItems 注册物品时用（与 ModEnchantments.getInitialDamageTypes 同模式）。
     * 不存在则生成默认配置并写入磁盘；存在则读取后按 damage_types.json 自动补全缺失伤害类型的饰品（写入磁盘）。
     * 注意：本方法只读最小必要信息（id + damageType + frameSpeed），用于注册物品；
     *       详细效果（增伤数值等）由 loadOrCreateCharmsConfig() 在 ServerStartingEvent 时正式加载到 CHARM_CACHE。
     *       新增饰品（用户修改 charms.json 加新条目）需重启游戏才能注册对应物品，与新增伤害类型需重启生成附魔的限制一致。
     */
    public static List<CharmConfig> getInitialCharms() {
        if (!CHARM_CACHE.isEmpty()) return new ArrayList<>(CHARM_CACHE.values());
        try {
            if (!Files.exists(CHARMS_FILE)) {
                // 首次生成默认配置（无染色，显示贴图原色）
                charmDefaultFrameSpeed = 8;
                for (String type : DEFAULT_DAMAGE_TYPES) {
                    CHARM_CACHE.put(type + "_charm", new CharmConfig(
                            type + "_charm", type + " 宝石", type,
                            0.15f, 0.10f, 0, 0,
                            defaultFramesFor(type), singleOrder(), charmDefaultFrameSpeed, "", 0f));
                }
                saveCharmsConfig();
                DamageOverhaulMod.LOGGER.info("Pre-generated default charms config ({} 个饰品)", CHARM_CACHE.size());
                return new ArrayList<>(CHARM_CACHE.values());
            }
            // 读现有配置
            try (Reader reader = new InputStreamReader(Files.newInputStream(CHARMS_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                charmDefaultFrameSpeed = root.has("default_frame_speed") ? root.get("default_frame_speed").getAsInt() : 8;
                JsonArray charms = root.has("charms") ? root.getAsJsonArray("charms") : new JsonArray();
                for (JsonElement e : charms) {
                    JsonObject c = e.getAsJsonObject();
                    String id = c.has("id") ? c.get("id").getAsString() : null;
                    String type = c.has("damage_type") ? c.get("damage_type").getAsString() : null;
                    if (id == null || type == null) continue;
                    JsonObject fx = c.has("effects") ? c.getAsJsonObject("effects") : new JsonObject();
                    float atkBonus = fx.has("attack_damage_bonus") ? fx.get("attack_damage_bonus").getAsFloat() : 0;
                    float defRes = fx.has("defense_resistance") ? fx.get("defense_resistance").getAsFloat() : 0;
                    float atkFlat = fx.has("attack_damage_flat") ? fx.get("attack_damage_flat").getAsFloat() : 0;
                    float defFlat = fx.has("defense_resistance_flat") ? fx.get("defense_resistance_flat").getAsFloat() : 0;
                    float toughness = fx.has("toughness") ? fx.get("toughness").getAsFloat() : 0;
                    JsonObject tex = c.has("texture") ? c.getAsJsonObject("texture") : new JsonObject();
                    List<String> frames = new ArrayList<>();
                    if (tex.has("frames")) {
                        for (JsonElement f : tex.getAsJsonArray("frames")) frames.add(f.getAsString());
                    }
                    if (frames.isEmpty()) frames = defaultFramesFor(type);
                    List<Integer> order = new ArrayList<>();
                    if (tex.has("frame_order")) {
                        for (JsonElement o : tex.getAsJsonArray("frame_order")) order.add(o.getAsInt());
                    }
                    if (order.isEmpty()) order.add(0);
                    int speed = tex.has("frame_speed") ? tex.get("frame_speed").getAsInt() : charmDefaultFrameSpeed;
                    CHARM_CACHE.put(id, new CharmConfig(id,
                            c.has("display_name") ? c.get("display_name").getAsString() : id,
                            type, atkBonus, defRes, atkFlat, defFlat,
                            frames, order, speed, "", toughness));
                }
            }
            // 自动补全缺失伤害类型的饰品（新增类型也走这里，写入 charms.json）
            autoFillMissingCharms();
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("getInitialCharms failed", e);
        }
        return new ArrayList<>(CHARM_CACHE.values());
    }

    public static WeaponDistribution getWeaponDistribution(ResourceLocation itemId) {
        return WEAPON_CACHE.getOrDefault(itemId, null);
    }

    public static WeaponDistribution getUnarmedDistribution(ResourceLocation entityId) {
        return UNARMED_CACHE.getOrDefault(entityId, null);
    }

    /**
     * 自动学习：为无分布配置的远程来源武器添加默认条目（穿刺100%）并立即写入配置文件。
     * 用于兼容"枪械类"模组（如 Scorched Guns、TACZ）：这类武器伤害由射弹结算，
     * 启动时无法泛化识别，只能在首次造成远程伤害时自动学习。
     *
     * @param itemId 来源武器物品ID（射手主手/副手）
     * @return true = 本次新增了条目
     */
    public static boolean addLearnedWeapon(ResourceLocation itemId) {
        if (itemId == null || WEAPON_CACHE.containsKey(itemId)) return false;
        Map<String, Float> distMap = new HashMap<>();
        for (String type : damageTypes) distMap.put(type, 0f);
        distMap.put("piercing", 1.0f);
        WeaponDistribution wd = new WeaponDistribution();
        wd.displayName = itemId.getPath();
        wd.damageDistribution = distMap;
        WEAPON_CACHE.put(itemId, wd);
        logMigration("weapon_distributions: 自动学习远程武器 " + itemId + "（默认穿刺100%，可手动修改分布）");
        saveWeaponConfig();
        DamageOverhaulMod.LOGGER.info("[AutoLearn] 新增远程武器分布条目: {}（默认穿刺100%）", itemId);
        return true;
    }

    public static boolean isAutoLearnRangedWeapons() {
        return autoLearnRangedWeapons;
    }

    public static ArmorResistance getArmorResistance(ResourceLocation itemId) {
        return ARMOR_CACHE.getOrDefault(itemId, null);
    }

    public static List<String> getDamageTypes() {
        return damageTypes;
    }

    public static String getRangedDamageSource() {
        return rangedDamageSource;
    }

    /**
     * 获取指定射弹实际使用的伤害来源。
     * follow_default=true → 使用全局默认 rangedDamageSource
     * follow_default=false → 使用与全局默认相反的值
     * @param projectileId 射弹实体ID
     * @return "weapon" 或 "projectile"，若射弹未配置则返回全局默认
     */
    public static String getRangedDamageSourceForProjectile(ResourceLocation projectileId) {
        ProjectileConfig cfg = PROJECTILE_CACHE.get(projectileId);
        if (cfg == null) return rangedDamageSource; // 未配置的射弹遵循默认
        if (cfg.followDefault) return rangedDamageSource;
        // 拒绝默认 → 使用相反值
        return "weapon".equals(rangedDamageSource) ? "projectile" : "weapon";
    }

    public static Map<String, Float> getProjectileDistribution(ResourceLocation entityId) {
        ProjectileConfig cfg = PROJECTILE_CACHE.get(entityId);
        return (cfg != null) ? cfg.damageDistribution : null;
    }

    /** 获取所有射弹配置（用于 status 命令展示） */
    public static Map<ResourceLocation, ProjectileConfig> getAllProjectileConfigs() {
        return Collections.unmodifiableMap(PROJECTILE_CACHE);
    }

    public static boolean isDeflectionEnabled() {
        return deflectionEnabled;
    }

    public static float getDeflectionX() {
        return deflectionX;
    }

    public static float getDeflectionZ() {
        return deflectionZ;
    }

    public static boolean isDeflectionAffected(String damageType) {
        return deflectionAffectedTypes.contains(damageType);
    }

    /** 热路径调试日志开关（special_mechanics.json: enable_debug_logging，可 reload 热切换） */
    public static boolean isDebugLogging() {
        return debugLoggingEnabled;
    }

    public static boolean isManaRepairEnabled() {
        return manaRepairEnabled;
    }

    public static int getDefaultManaPerDurability() {
        return defaultManaPerDurability;
    }

    public static ManaRepairEntry getManaRepairEntry(ResourceLocation itemId) {
        return MANA_REPAIR_CACHE.get(itemId);
    }

    public static Map<ResourceLocation, ManaRepairEntry> getAllManaRepairEntries() {
        return MANA_REPAIR_CACHE;
    }

    public static boolean isStaticRepairEnabled() {
        return staticRepairEnabled;
    }

    public static int getStaticRepairInterval() {
        return staticRepairInterval;
    }

    public static int getStaticRepairPerTick() {
        return staticRepairPerTick;
    }

    // ======================== 网络同步：snapshot + applySyncedConfig ========================

    /** 返回武器分布快照（不可变深拷贝），用于 ModMessages 服务端→客户端同步 */
    public static Map<ResourceLocation, WeaponDistribution> getWeaponCacheSnapshot() {
        Map<ResourceLocation, WeaponDistribution> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, WeaponDistribution> e : WEAPON_CACHE.entrySet()) {
            WeaponDistribution src = e.getValue();
            WeaponDistribution dst = new WeaponDistribution();
            dst.displayName = src.displayName;
            dst.damageDistribution = new LinkedHashMap<>(src.damageDistribution == null ? Map.of() : src.damageDistribution);
            copy.put(e.getKey(), dst);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 返回护甲抗性快照（不可变深拷贝） */
    public static Map<ResourceLocation, ArmorResistance> getArmorCacheSnapshot() {
        Map<ResourceLocation, ArmorResistance> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ArmorResistance> e : ARMOR_CACHE.entrySet()) {
            ArmorResistance src = e.getValue();
            ArmorResistance dst = new ArmorResistance();
            dst.displayName = src.displayName;
            dst.resistances = new LinkedHashMap<>(src.resistances == null ? Map.of() : src.resistances);
            copy.put(e.getKey(), dst);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 返回射弹配置快照（不可变深拷贝） */
    public static Map<ResourceLocation, ProjectileConfig> getProjectileCacheSnapshot() {
        Map<ResourceLocation, ProjectileConfig> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ProjectileConfig> e : PROJECTILE_CACHE.entrySet()) {
            ProjectileConfig src = e.getValue();
            Map<String, Float> dist = new LinkedHashMap<>(src.damageDistribution == null ? Map.of() : src.damageDistribution);
            copy.put(e.getKey(), new ProjectileConfig(src.followDefault, dist));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 返回 mana repair 快照（不可变深拷贝） */
    public static Map<ResourceLocation, ManaRepairEntry> getManaRepairCacheSnapshot() {
        Map<ResourceLocation, ManaRepairEntry> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ManaRepairEntry> e : MANA_REPAIR_CACHE.entrySet()) {
            ManaRepairEntry src = e.getValue();
            copy.put(e.getKey(), new ManaRepairEntry(src.enabled, src.manaPerDurability));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 返回 display 配置快照（不可变拷贝） */
    public static Map<String, Boolean> getDisplayConfigSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(DISPLAY_CONFIG));
    }

    /**
     * 客户端专用：应用来自服务端同步包的配置。
     * 覆盖所有影响 tooltip 显示和伤害结算的缓存。
     * 被 ModMessages.ConfigSyncPacket.handle 在客户端主线程调用。
     */
    public static void applySyncedConfig(List<String> newDamageTypes,
                                         Map<ResourceLocation, WeaponDistribution> newWeapons,
                                         Map<ResourceLocation, ArmorResistance> newArmors,
                                         Map<ResourceLocation, ProjectileConfig> newProjectiles,
                                         Map<ResourceLocation, ManaRepairEntry> newMana,
                                         String newRangedSource,
                                         Map<String, Boolean> newDisplay,
                                         Map<ResourceLocation, ProjectileItemMapping> newProjItemMap,
                                         boolean newAllowDangerousReplace,
                                         boolean newExternalCharmsEnabled,
                                         Map<String, ExternalCharmConfig> newExternalCharms,
                                         boolean newMobEffectsEnabled,
                                         Map<String, MobEffectValues> newMobEffects,
                                         boolean newWeaponScalingEnabled,
                                         Map<String, ScalingRow> newWeaponScaling,
                                         boolean newArmorScalingEnabled,
                                         Map<String, ScalingRow> newArmorScaling,
                                         List<String> newScalingAttributes,
                                         Map<String, WeaponGuardBreak> newWeaponGuardBreak,
                                         Map<String, ArmorToughness> newArmorToughness,
                                         Map<String, ImpactEffect> newImpactEffects) {
        damageTypes = new ArrayList<>(newDamageTypes);

        WEAPON_CACHE.clear();
        WEAPON_CACHE.putAll(newWeapons == null ? Map.of() : newWeapons);

        ARMOR_CACHE.clear();
        ARMOR_CACHE.putAll(newArmors == null ? Map.of() : newArmors);

        PROJECTILE_CACHE.clear();
        PROJECTILE_CACHE.putAll(newProjectiles == null ? Map.of() : newProjectiles);

        MANA_REPAIR_CACHE.clear();
        MANA_REPAIR_CACHE.putAll(newMana == null ? Map.of() : newMana);

        if (newRangedSource != null && !newRangedSource.isEmpty()) {
            rangedDamageSource = newRangedSource;
        }

        if (newDisplay != null && !newDisplay.isEmpty()) {
            DISPLAY_CONFIG.clear();
            DISPLAY_CONFIG.putAll(newDisplay);
        }

        // 射弹物品映射：优先使用服务端同步的映射（含 replace_entity 标记）
        // 再补充客户端自动扫描的同名/标签映射
        PROJ_ITEM_MAP.clear();
        if (newProjItemMap != null && !newProjItemMap.isEmpty()) {
            for (Map.Entry<ResourceLocation, ProjectileItemMapping> e : newProjItemMap.entrySet()) {
                PROJ_ITEM_MAP.put(e.getKey(),
                        new ProjectileItemMapping(e.getValue().entityId, e.getValue().replaceEntity));
            }
        }
        // 客户端侧自动扫描补充（不覆盖已有映射）
        autoScanProjectileItems();
        // 同步 dangerous_replace 开关（客户端 tooltip 显示需要）
        allowDangerousReplace = newAllowDangerousReplace;
        // 同步第三方饰品配置（客户端 tooltip 显示需要）
        externalCharmsEnabled = newExternalCharmsEnabled;
        EXTERNAL_CHARM_CACHE.clear();
        if (newExternalCharms != null) {
            EXTERNAL_CHARM_CACHE.putAll(newExternalCharms);
        }
        // 同步药水效果数值（客户端结算预览/tooltip 一致性需要）
        mobEffectsEnabled = newMobEffectsEnabled;
        MOB_EFFECT_CACHE.clear();
        if (newMobEffects != null) {
            MOB_EFFECT_CACHE.putAll(newMobEffects);
        }
        // 同步装备补正表（稀疏非0条目；客户端 tooltip 需要，客户端侧不参与伤害结算）
        weaponScalingEnabled = newWeaponScalingEnabled;
        armorScalingEnabled = newArmorScalingEnabled;
        WEAPON_SCALING_CACHE.clear();
        ARMOR_SCALING_CACHE.clear();
        if (newWeaponScaling != null) WEAPON_SCALING_CACHE.putAll(newWeaponScaling);
        if (newArmorScaling != null) ARMOR_SCALING_CACHE.putAll(newArmorScaling);
        if (newScalingAttributes != null && !newScalingAttributes.isEmpty()) {
            scalingAttributes = new ArrayList<>(newScalingAttributes);
        }
        // 同步冲击力/韧性系统缓存（客户端 tooltip 显示需要）
        WEAPON_GUARD_BREAK_CACHE.clear();
        if (newWeaponGuardBreak != null) WEAPON_GUARD_BREAK_CACHE.putAll(newWeaponGuardBreak);
        ARMOR_TOUGHNESS_CACHE.clear();
        if (newArmorToughness != null) ARMOR_TOUGHNESS_CACHE.putAll(newArmorToughness);
        IMPACT_EFFECTS_CACHE.clear();
        if (newImpactEffects != null) IMPACT_EFFECTS_CACHE.putAll(newImpactEffects);
    }

    // ======================== 显示开关 API（display_config.json） ========================

    public static boolean showWeaponDistribution()   { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_weapon_distribution", true)); }
    public static boolean showArmorResistance()      { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_armor_resistance", true)); }
    public static boolean showProjectileDistribution(){ return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_projectile_distribution", true)); }
    public static boolean showManaRepair()           { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_mana_repair", true)); }
    public static boolean showExternalCharmBonus()   { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_external_charm_bonus", true)); }
    public static boolean showEquipmentScaling()     { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_equipment_scaling", true)); }
    public static boolean showWeaponGuardBreak()     { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_weapon_guard_break", true)); }
    public static boolean showArmorToughness()      { return Boolean.TRUE.equals(DISPLAY_CONFIG.getOrDefault("show_armor_toughness", true)); }

    // ======================== 射弹附魔加成等级配置 ========================

    /** 射弹附魔独立 schema（与主配置 schema 解耦，新配置文件首次创建版本为 v1） */
    private static final int PROJ_STRIKE_SCHEMA = 1;

    /** 获取 projectile_strike_<damage_type> 附魔各等级的「占比加成数值」（完全配置化，无硬编码）。
     *  Map key = 等级字符串 "1"/"2"/...，value = 对应类型的额外占比（如默认 Lv4 → +0.4）。 */
    public static Map<String, Float> getProjectileStrikeBonusLevels() {
        if (PROJECTILE_STRIKE_BONUS_LEVELS.isEmpty()) {
            // 尚未加载（服务端启动前若被调用）→ 使用硬编码默认（保证非空）
            Map<String, Float> d = new LinkedHashMap<>();
            d.put("1", 0.1f); d.put("2", 0.2f); d.put("3", 0.3f); d.put("4", 0.4f);
            return d;
        }
        return Collections.unmodifiableMap(PROJECTILE_STRIKE_BONUS_LEVELS);
    }

    /** 默认 bonus_levels（不存在配置文件时使用） */
    private static Map<String, Float> getDefaultProjectileStrikeBonusLevels() {
        Map<String, Float> d = new LinkedHashMap<>();
        d.put("1", 0.1f); d.put("2", 0.2f); d.put("3", 0.3f); d.put("4", 0.4f);
        return d;
    }

    /** 加载/生成 projectile_strike_config.json（独立 schema v1） */
    private static void loadOrCreateProjectileStrikeConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) Files.createDirectories(CONFIG_PATH);
            if (!Files.exists(PROJECTILE_STRIKE_FILE)) {
                PROJECTILE_STRIKE_BONUS_LEVELS.clear();
                PROJECTILE_STRIKE_BONUS_LEVELS.putAll(getDefaultProjectileStrikeBonusLevels());
                saveProjectileStrikeConfig();
                DamageOverhaulMod.LOGGER.info("Generated default projectile_strike config: {}", PROJECTILE_STRIKE_FILE);
                return;
            }
            try (Reader reader = new InputStreamReader(Files.newInputStream(PROJECTILE_STRIKE_FILE), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                boolean needUpdate = false;

                // schema 版本检查（独立 v1）
                if (!root.has("_schema_version")) {
                    logMigration("projectile_strike_config.json: 缺少 _schema_version，已补 v" + PROJ_STRIKE_SCHEMA);
                    needUpdate = true;
                } else {
                    int v = root.get("_schema_version").getAsInt();
                    if (v < PROJ_STRIKE_SCHEMA) {
                        logMigration("projectile_strike_config.json: _schema_version v" + v + " → v" + PROJ_STRIKE_SCHEMA);
                        needUpdate = true;
                    }
                }

                // 缺少 bonus_levels 字段 → 用默认
                if (!root.has("bonus_levels") || !root.get("bonus_levels").isJsonObject()) {
                    logMigration("projectile_strike_config.json: 缺少字段 bonus_levels，已使用默认值");
                    needUpdate = true;
                }

                PROJECTILE_STRIKE_BONUS_LEVELS.clear();
                JsonObject bonus = (root.has("bonus_levels") && root.get("bonus_levels").isJsonObject())
                        ? root.getAsJsonObject("bonus_levels") : null;
                Map<String, Float> def = getDefaultProjectileStrikeBonusLevels();
                if (bonus != null) {
                    for (Map.Entry<String, JsonElement> e : bonus.entrySet()) {
                        String level = e.getKey();
                        try {
                            float val = e.getValue().getAsFloat();
                            if (val < 0) { val = 0; needUpdate = true;
                                logMigration("projectile_strike_config.json: bonus_levels[" + level + "] 为负数，已修正为 0"); }
                            PROJECTILE_STRIKE_BONUS_LEVELS.put(level, val);
                        } catch (Exception ex) {
                            logMigration("projectile_strike_config.json: bonus_levels[" + level + "] 非法，使用默认值 " + def.get(level));
                            if (def.containsKey(level)) PROJECTILE_STRIKE_BONUS_LEVELS.put(level, def.get(level));
                            needUpdate = true;
                        }
                    }
                }
                // 补齐缺失的默认条目（例如新版本新增的等级）
                for (Map.Entry<String, Float> d : def.entrySet()) {
                    if (!PROJECTILE_STRIKE_BONUS_LEVELS.containsKey(d.getKey())) {
                        PROJECTILE_STRIKE_BONUS_LEVELS.put(d.getKey(), d.getValue());
                        logMigration("projectile_strike_config.json: 新增默认 bonus_levels " + d.getKey() + " = " + d.getValue());
                        needUpdate = true;
                    }
                }

                if (needUpdate) {
                    backupBeforeSave(PROJECTILE_STRIKE_FILE, "projectile strike config migration");
                    saveProjectileStrikeConfig();
                }
                DamageOverhaulMod.LOGGER.info("Loaded projectile_strike bonus_levels (共 {} 级): {}",
                        PROJECTILE_STRIKE_BONUS_LEVELS.size(), PROJECTILE_STRIKE_BONUS_LEVELS);
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load projectile_strike_config", e);
            PROJECTILE_STRIKE_BONUS_LEVELS.clear();
            PROJECTILE_STRIKE_BONUS_LEVELS.putAll(getDefaultProjectileStrikeBonusLevels());
        }
    }

    private static void saveProjectileStrikeConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", PROJ_STRIKE_SCHEMA);
        root.addProperty("_comment",
                "projectile_strike_<damage_type>附魔各等级的『占比加成数值』。键=等级字符串(1/2/...)；值=对应类型额外占比增量（加在基础射弹分布上，不降低其他类型）。完全无硬编码，可随时修改。");
        JsonObject bonus = new JsonObject();
        for (Map.Entry<String, Float> e : PROJECTILE_STRIKE_BONUS_LEVELS.entrySet()) {
            bonus.addProperty(e.getKey(), e.getValue());
        }
        root.add("bonus_levels", bonus);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(PROJECTILE_STRIKE_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save projectile_strike_config", e);
        }
    }

    // ======================== 显示开关配置（display_config.json） ========================

    /** display_config 独立 schema（仅与显示相关，不影响伤害结算逻辑） */
    private static final int DISPLAY_SCHEMA = 2;

    /** 加载或生成 display_config.json（4 类显示开关，全部默认开启） */
    private static void loadOrCreateDisplayConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) Files.createDirectories(CONFIG_PATH);
            if (!Files.exists(DISPLAY_CONFIG_FILE)) {
                saveDisplayConfig();
                DamageOverhaulMod.LOGGER.info("Generated default display config: {}", DISPLAY_CONFIG_FILE);
                return;
            }

            JsonElement el = JsonParser.parseReader(
                    new InputStreamReader(Files.newInputStream(DISPLAY_CONFIG_FILE), StandardCharsets.UTF_8));
            if (!el.isJsonObject()) {
                DamageOverhaulMod.LOGGER.warn("display_config.json 非 object，使用默认显示配置");
                return;
            }
            JsonObject root = el.getAsJsonObject();
            boolean needsSave = false;

            // 兼容：如果缺 schema 或低于当前 → 新增缺省项并保存
            int sv = root.has("_schema_version") ? root.get("_schema_version").getAsInt() : 0;
            if (sv < DISPLAY_SCHEMA) {
                needsSave = true;
                logMigration("display_config: schema " + sv + "→" + DISPLAY_SCHEMA + "（补齐缺失开关）");
            }

            // 逐项读取（未知键忽略；缺键使用默认值）
            for (String key : DISPLAY_CONFIG.keySet()) {
                if (root.has(key)) {
                    DISPLAY_CONFIG.put(key, root.get(key).getAsBoolean());
                } else {
                    needsSave = true; // 新增缺失项
                    logMigration("display_config: 新增开关 " + key + " = " + DISPLAY_CONFIG.get(key));
                }
            }

            if (needsSave) {
                saveDisplayConfig();
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load display config", e);
        }
    }

    private static void saveDisplayConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", DISPLAY_SCHEMA);
        root.addProperty("_comment",
                "物品 tooltip 显示开关。true=显示；false=隐藏。被服务端推送覆盖时此本地文件可能不生效（见 /damageoverhaul status 的 sync 来源）");
        for (Map.Entry<String, Boolean> e : DISPLAY_CONFIG.entrySet()) {
            root.addProperty(e.getKey(), e.getValue());
        }
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(DISPLAY_CONFIG_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save display_config", e);
        }
    }

    // ======================== 射弹物品映射配置（projectile_item_mappings.json） ========================

    /** 映射独立 schema */
    private static final int PROJ_ITEM_MAP_SCHEMA = 2;

    /** 加载/生成 projectile_item_mappings.json，并自动扫描补充 */
    private static void loadOrCreateProjectileItemMap() {
        try {
            if (!Files.exists(CONFIG_PATH)) Files.createDirectories(CONFIG_PATH);
            if (!Files.exists(PROJ_ITEM_MAP_FILE)) {
                PROJ_ITEM_MAP.clear();
                allowDangerousReplace = false;
                autoScanProjectileItems();
                saveProjectileItemMap();
                DamageOverhaulMod.LOGGER.info("Generated projectile_item_mappings: {} (auto-scanned)", PROJ_ITEM_MAP_FILE);
                return;
            }

            JsonElement el = JsonParser.parseReader(
                    new InputStreamReader(Files.newInputStream(PROJ_ITEM_MAP_FILE), StandardCharsets.UTF_8));
            if (!el.isJsonObject()) {
                DamageOverhaulMod.LOGGER.warn("projectile_item_mappings.json 非 object，使用自动扫描");
                PROJ_ITEM_MAP.clear();
                allowDangerousReplace = false;
                autoScanProjectileItems();
                return;
            }
            JsonObject root = el.getAsJsonObject();
            boolean needsSave = false;

            int sv = root.has("_schema_version") ? root.get("_schema_version").getAsInt() : 0;
            if (sv < PROJ_ITEM_MAP_SCHEMA) {
                needsSave = true;
                logMigration("projectile_item_mappings: schema " + sv + "→" + PROJ_ITEM_MAP_SCHEMA);
            }

            // allow_dangerous_replace
            allowDangerousReplace = root.has("allow_dangerous_replace") && root.get("allow_dangerous_replace").getAsBoolean();

            // 读取 mappings（兼容 v1 string 格式和 v2 object 格式）
            PROJ_ITEM_MAP.clear();
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                JsonObject mappings = root.getAsJsonObject("mappings");
                for (Map.Entry<String, JsonElement> e : mappings.entrySet()) {
                    try {
                        ResourceLocation itemId = new ResourceLocation(e.getKey());
                        JsonElement val = e.getValue();
                        if (val.isJsonObject()) {
                            // v2 格式：{ "entity": "minecraft:tnt", "replace_entity": true }
                            JsonObject obj = val.getAsJsonObject();
                            ResourceLocation entityId = new ResourceLocation(obj.get("entity").getAsString());
                            boolean replace = obj.has("replace_entity") && obj.get("replace_entity").getAsBoolean();
                            PROJ_ITEM_MAP.put(itemId, new ProjectileItemMapping(entityId, replace));
                        } else if (val.isJsonPrimitive()) {
                            // v1 格式：直接是 string（迁移为 replace_entity=false）
                            ResourceLocation entityId = new ResourceLocation(val.getAsString());
                            PROJ_ITEM_MAP.put(itemId, new ProjectileItemMapping(entityId, false));
                            needsSave = true;
                            logMigration("projectile_item_mappings: 迁移 " + itemId + " 从 v1 string→v2 object");
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 如果 v1→v2 迁移了，补充 allow_dangerous_replace 字段
            if (sv < 2) {
                needsSave = true;
            }

            // 自动扫描补充
            int before = PROJ_ITEM_MAP.size();
            autoScanProjectileItems();
            if (PROJ_ITEM_MAP.size() > before) {
                needsSave = true;
                logMigration("projectile_item_mappings: 自动扫描新增 " + (PROJ_ITEM_MAP.size() - before) + " 项映射");
            }

            if (needsSave) {
                saveProjectileItemMap();
            }
        } catch (Exception e) {
            DamageOverhaulMod.LOGGER.error("Failed to load projectile_item_mappings", e);
        }
    }

    /**
     * 自动扫描注册表，为 PROJECTILE_CACHE 中的射弹实体补充物品映射。
     * 不硬编码任何具体物品，完全基于注册表和标签：
     * 1) 同名匹配：物品ID = 实体ID（如 minecraft:arrow → minecraft:arrow）
     * 2) 标签扫描：扫描 minecraft:arrows / forge:arrows 等 item tag，
     *    所有标签内成员映射到对应的 minecraft:arrow 实体
     * 3) 三叉戟特例：minecraft:trident 物品 → minecraft:trident 实体（同名匹配已覆盖）
     */
    private static void autoScanProjectileItems() {
        // 1) 同名匹配：对 PROJECTILE_CACHE 中每个实体 ID，检查是否有同名物品
        for (ResourceLocation entityId : PROJECTILE_CACHE.keySet()) {
            if (!PROJ_ITEM_MAP.containsKey(entityId)) { // 不覆盖已有
                if (BuiltInRegistries.ITEM.containsKey(entityId)) {
                    PROJ_ITEM_MAP.put(entityId, new ProjectileItemMapping(entityId, false));
                }
            }
        }

        // 2) 标签扫描：对常见箭类标签，将所有成员映射到 minecraft:arrow
        String[][] arrowTags = {
            {"minecraft", "arrows"},
            {"forge", "arrows"},
        };
        for (String[] tag : arrowTags) {
            TagKey<Item> tagKey = TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    new ResourceLocation(tag[0], tag[1]));
            var tagEntries = BuiltInRegistries.ITEM.getTag(tagKey);
            if (tagEntries.isPresent()) {
                for (var holder : tagEntries.get()) {
                    Item item = holder.value();
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
                    ResourceLocation arrowEntity = new ResourceLocation("minecraft", "arrow");
                    if (!PROJ_ITEM_MAP.containsKey(itemId) && PROJECTILE_CACHE.containsKey(arrowEntity)) {
                        PROJ_ITEM_MAP.put(itemId, new ProjectileItemMapping(arrowEntity, false));
                    }
                }
            }
        }
    }

    private static void saveProjectileItemMap() {
        JsonObject root = new JsonObject();
        root.addProperty("_schema_version", PROJ_ITEM_MAP_SCHEMA);
        root.addProperty("allow_dangerous_replace", allowDangerousReplace);
        root.addProperty("_comment",
                "物品→射弹实体映射。replace_entity=false: 仅tooltip显示该实体的分布；replace_entity=true: 实际替换生成的实体。allow_dangerous_replace: 控制是否允许替换为非射弹实体（如TNT）。自动扫描补充，可手动添加。");
        JsonObject mappings = new JsonObject();
        for (Map.Entry<ResourceLocation, ProjectileItemMapping> e : PROJ_ITEM_MAP.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("entity", e.getValue().entityId.toString());
            entry.addProperty("replace_entity", e.getValue().replaceEntity);
            mappings.add(e.getKey().toString(), entry);
        }
        root.add("mappings", mappings);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(PROJ_ITEM_MAP_FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            DamageOverhaulMod.LOGGER.error("Failed to save projectile_item_mappings", e);
        }
    }

    /** 查询物品对应的射弹实体ID（用于 tooltip 匹配） */
    public static ProjectileItemMapping getProjectileMappingForItem(ResourceLocation itemId) {
        return PROJ_ITEM_MAP.get(itemId);
    }

    /** 旧的兼容方法：返回 entityId（仅 tooltip 用途） */
    public static ResourceLocation getProjectileEntityForItem(ResourceLocation itemId) {
        ProjectileItemMapping m = PROJ_ITEM_MAP.get(itemId);
        return m != null ? m.entityId : null;
    }

    /** 返回映射快照（用于网络同步） */
    public static Map<ResourceLocation, ProjectileItemMapping> getProjItemMapSnapshot() {
        Map<ResourceLocation, ProjectileItemMapping> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ProjectileItemMapping> e : PROJ_ITEM_MAP.entrySet()) {
            copy.put(e.getKey(), new ProjectileItemMapping(e.getValue().entityId, e.getValue().replaceEntity));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static boolean isAllowDangerousReplace() { return allowDangerousReplace; }

    // ======================== 数据类 ========================
    public static class EntityResistance {
        public String displayName;
        public String translationKey;
        public Map<String, Float> modifiers;
        public float baseToughness = 0f;
        /** 免疫的冲击效果 ID 列表（冲击力 A 被无视） */
        public List<String> immuneImpactEffects = new ArrayList<>();
        /** 冲击效果重映射 Map（冲击力 D 被视为冲击力 C，key=原效果ID, value=替换效果ID） */
        public Map<String, String> impactEffectRemap = new LinkedHashMap<>();
    }

    public static class WeaponDistribution {
        public String displayName;
        public Map<String, Float> damageDistribution;
        /** 削韧值（空手攻击时使用，武器时从 weapon_guard_break.json 读取） */
        public float guardBreak = 0f;
        /** 关联冲击效果 ID（空手攻击时使用，武器时从 weapon_guard_break.json 读取） */
        public String impactEffect = null;
    }

    /** 方块源性伤害分布条目：use_default=true 走默认链路（damage_type_mappings），false 使用本条目分布 */
    public static class BlockDistEntry {
        public boolean useDefault = true;
        public Map<String, Float> damageDistribution = new LinkedHashMap<>();
    }

    public static class ArmorResistance {
        public String displayName;
        public Map<String, Float> resistances;
    }

    // ======================== 冲击力/韧性系统数据类 ========================

    /** 武器削韧值 + 关联冲击效果ID */
    public static class WeaponGuardBreak {
        public final String itemId;
        public final float guardBreak;        // 削韧值
        public final String impactEffect;     // 引用 impact_effects.json 中的 effect ID（可为 null）
        public WeaponGuardBreak(String itemId, float guardBreak, String impactEffect) {
            this.itemId = itemId;
            this.guardBreak = guardBreak;
            this.impactEffect = impactEffect;
        }
    }

    /** 护甲韧性 */
    public static class ArmorToughness {
        public final String itemId;
        public final float toughness;           // 韧性（抵御削韧，4件叠加）
        public ArmorToughness(String itemId, float toughness) {
            this.itemId = itemId;
            this.toughness = toughness;
        }
    }

    /** 冲击效果预设 */
    public static class ImpactEffect {
        public final String id;
        /** 上下方向：+为上升，-为下降，0为无 */
        public final float upDown;
        /** 左右方向：+为右，-为左，0为无 */
        public final float leftRight;
        /** 前后方向：+为前，-为后，0为无 */
        public final float forwardBackward;
        public final float distance;       // 位移距离（格）
        public final int durationTick;     // 受影响 tick（玩家=stun/freeze 时长）
        public final int aiPauseTick;      // AI 暂停 tick（mob 专用）
        public ImpactEffect(String id, float upDown, float leftRight, float forwardBackward,
                            float distance, int durationTick, int aiPauseTick) {
            this.id = id;
            this.upDown = upDown;
            this.leftRight = leftRight;
            this.forwardBackward = forwardBackward;
            this.distance = distance;
            this.durationTick = durationTick;
            this.aiPauseTick = aiPauseTick;
        }
    }
}
