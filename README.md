# Damage Overhaul

把 Minecraft 原版的「一个伤害数字」拆成 **8 种伤害类型的分布**，并在此基础上叠加抗性、护甲、附魔、饰品与武器补正，形成一套可配置的伤害结算系统。

## 它做了什么

一次命中不再是一条固定数值，而是一组按比例分配的伤害类型：

| 类型 | id |
|---|---|
| 斩击 | `slashing` |
| 刺击 | `piercing` |
| 钝击 | `blunt` |
| 火焰 | `fire` |
| 雷电 | `lightning` |
| 魔法 | `magic` |
| 神圣 | `holy` |
| 暗黑 | `dark` |

结算顺序：

```
武器/攻击的分布表  →  拆成 8 类
   ↓
逐类叠加：生物抗性、护甲、附魔、饰品、武器补正
   ↓
韧性与削韧、格挡破防、击退与硬直
   ↓
合并为实际伤害
```

附带系统：弹道伤害映射、护甲减伤、饰品（Curios）与法器、药水效果（增伤/抗性/削弱/易伤）、冲击力与韧性、法术书兼容。

所有数值都是 JSON，位于 `config/damageoverhaul/`，不需要改代码。

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.2.6 及以上（47.x） |
| Curios API | 必需（饰品系统） |
| Better Combat / Iron's Spells / Patchouli | 可选，装了会自动适配 |

## 安装

1. 安装 Minecraft Forge 1.20.1（47.x）
2. 把 `damageoverhaul-1.0.0.jar` 和 Curios 一起放进 `mods/`
3. 首次启动会在 `config/damageoverhaul/` 生成全部配置与备份目录

## 配置

主要文件（均在 `config/damageoverhaul/`）：

```
damage_type_mappings.json     伤害类型映射（注册表类型 → 8 类）
weapon_distributions.json     武器伤害分布
unarmed_distributions.json    空手/生物攻击分布
armor_resistances.json        护甲抗性
entity_resistances.json       生物抗性
ranged_config.json            弹道与投射物
deflection.json               弹反
weapon_guard_break.json       武器破防
armor_toughness.json          护甲韧性
impact_effects.json           冲击效果
charms.json / external_charms.json   饰品
mob_effects.json              药水效果
weapon_scaling.json / armor_scaling.json  补正
player_config.json            玩家全局参数
```

配置文件带版本迁移：字段缺失按默认值处理，旧格式会在加载时自动升级，并在 `backups/` 留备份。

## 从源码构建

需要 JDK 17（必须是 JDK，不能是 JRE）。

```bash
# Windows
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat build

# Linux / macOS
export JAVA_HOME=/path/to/jdk-17
./gradlew build
```

产物：`build/libs/damageoverhaul-1.0.0.jar`

## 许可证

MIT，见 [LICENSE.txt](LICENSE.txt)。

## 作者

ZeroChen
