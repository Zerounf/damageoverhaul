package com.zerochen.damageoverhaul.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zerochen.damageoverhaul.DamageOverhaulMod;
import com.zerochen.damageoverhaul.config.ConfigLoader;
import com.zerochen.damageoverhaul.config.ConfigLoader.CharmConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 动态饰品模型数据包：以内存 CLIENT_RESOURCES 包为每个饰品生成独立的物品模型 + 帧模型，
 * 使每个饰品可拥有独立贴图与手绘动画（不再共享 charm_base 贴图）。
 *
 * 模型贴图路径完全由 charms.json 的 texture.frames 驱动：
 *   - 第 i 帧引用 damageoverhaul:item/<textureFrames.get(i)>，即 assets/damageoverhaul/textures/item/<name>.png
 *   - 帧数 N = textureFrames.size()；N==1 时无动画（无 overrides，仅 layer0）；N>1 时按 frame 属性 overrides 0..N-1
 *   - frame 属性已由 CharmClientSetup 注册，按 frame_order 返回当前帧索引
 *
 * 默认配置 frames=["charm_base_0".."charm_base_3"] → 所有饰品共享基础贴图（与旧静态 charm_base 行为一致）；
 * 用户把某饰品 frames 改为 ["<id>_0", ...,"<id>_<N-1>"] 即启用该饰品的独立手绘贴图，
 * 再把对应 PNG 放入 assets/damageoverhaul/textures/item/（烘焙进 JAR）或外部 <game>/damageoverhaul/item/ 覆盖。
 *
 * 包为 required=true + Position.TOP：始终加载且覆盖 JAR 内同名静态模型文件（静态文件保留作降级兜底）。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class CharmModelDataPack {

    private static final String PACK_ID = "damageoverhaul_charm_models";
    private static final String NAMESPACE = "damageoverhaul";
    private static final String MODELS_DIR = "models/item/";
    private static final String TEXTURES_DIR = "textures/item/";

    /** 外部贴图覆盖目录 <game>/damageoverhaul/item/（与 ExternalTexturePackFinder 一致）。
     *  本包提供贴图时优先让外部文件覆盖 jar 内置贴图：若外部存在同名 PNG，本包返回 null
     *  让底层 ExternalTexturePackFinder(BOTTOM) 提供外部版本，保留用户覆盖能力。 */
    private static final Path EXTERNAL_TEXTURE_DIR;
    static {
        Path d = null;
        try { d = FMLPaths.GAMEDIR.get().resolve("damageoverhaul").resolve("item"); }
        catch (Throwable ignored) { d = null; }
        EXTERNAL_TEXTURE_DIR = d;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        event.addRepositorySource(new CharmModelRepositorySource());
        DamageOverhaulMod.LOGGER.info("[CharmModels] 注入动态饰品模型数据包（按 charms.json 生成每饰品独立模型）");
    }

    /** 生成饰品物品模型 JSON：<id>.json，第 0 帧作 layer0，N>1 时按 frame 属性 overrides 0..N-1。 */
    private static String buildItemModelJson(CharmConfig cfg) {
        List<String> frames = cfg.textureFrames;
        int n = frames.size();
        String layer0 = "damageoverhaul:item/" + (n > 0 ? frames.get(0) : "charm_base_0");
        JsonObject obj = new JsonObject();
        obj.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", layer0);
        obj.add("textures", tex);
        if (n > 1) {
            JsonArray overrides = new JsonArray();
            for (int i = 0; i < n; i++) {
                JsonObject entry = new JsonObject();
                JsonObject pred = new JsonObject();
                pred.addProperty("damageoverhaul:frame", (double) i);
                entry.add("predicate", pred);
                entry.addProperty("model", "damageoverhaul:item/" + cfg.id + "_frame_" + i);
                overrides.add(entry);
            }
            obj.add("overrides", overrides);
        }
        return new Gson().toJson(obj);
    }

    /** 生成帧模型 JSON：<id>_frame_<i>.json，引用第 i 帧贴图。 */
    private static String buildFrameModelJson(CharmConfig cfg, int i) {
        List<String> frames = cfg.textureFrames;
        String texName = (i >= 0 && i < frames.size()) ? frames.get(i) : "charm_base_0";
        JsonObject obj = new JsonObject();
        obj.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", "damageoverhaul:item/" + texName);
        obj.add("textures", tex);
        return new Gson().toJson(obj);
    }

    /** 按模型名（models/item/<name>.json 的 <name> 部分）返回对应 JSON，无匹配返回 null。 */
    private static String getJsonForModelName(String name) {
        for (CharmConfig cfg : ConfigLoader.getCharmConfigs()) {
            if (name.equals(cfg.id)) return buildItemModelJson(cfg);
            String framePrefix = cfg.id + "_frame_";
            if (name.startsWith(framePrefix)) {
                try {
                    int i = Integer.parseInt(name.substring(framePrefix.length()));
                    if (i >= 0 && i < cfg.textureFrames.size()) return buildFrameModelJson(cfg, i);
                } catch (NumberFormatException ignored) {
                    // 非数字后缀，跳过
                }
            }
        }
        return null;
    }

    public static class CharmModelRepositorySource implements RepositorySource {
        @Override
        public void loadPacks(Consumer<Pack> packConsumer) {
            Pack.Info info = new Pack.Info(
                    Component.literal("Damage Overhaul 饰品动态模型"),
                    15,
                    FeatureFlagSet.of()
            );
            Pack pack = Pack.create(
                    PACK_ID,
                    Component.literal("Damage Overhaul 饰品动态模型"),
                    true,                                       // required：始终加载，保证模型可用
                    packId -> new CharmModelResources(),
                    info,
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,                         // 高优先级，覆盖 JAR 内同名静态模型
                    false,
                    PackSource.BUILT_IN
            );
            packConsumer.accept(pack);
        }
    }

    public static class CharmModelResources implements PackResources {

        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... paths) {
            return null;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES) return null;
            if (!NAMESPACE.equals(location.getNamespace())) return null;
            String path = location.getPath();
            // 物品模型（按 charms.json 动态生成）
            if (path.startsWith(MODELS_DIR) && path.endsWith(".json")) {
                String name = path.substring(MODELS_DIR.length(), path.length() - ".json".length());
                String json = getJsonForModelName(name);
                return json == null ? null : () -> openJson(json);
            }
            // 贴图：直接从 mod jar 的 classpath 提供。
            // 某些整合包环境下 mod_resources(Forge 聚合包) 未能把 damageoverhaul 命名空间的贴图
            // 纳入运行时命名空间索引（与本包 required+TOP 声称该命名空间产生交互），导致模型能
            // 解析但贴图被报 Missing。本包处于 TOP 优先级且已声称 damageoverhaul 命名空间，
            // 在此直接返回 classpath 中的 PNG 可绕过该问题，保证饰品贴图正常显示。
            if (path.startsWith(TEXTURES_DIR) && path.endsWith(".png")) {
                return getTextureSupplier(path);
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path,
                                  PackResources.ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !NAMESPACE.equals(namespace)) return;
            if (pathMatches(path, "models/item")) {
                for (CharmConfig cfg : ConfigLoader.getCharmConfigs()) {
                    int n = cfg.textureFrames.size();
                    output.accept(new ResourceLocation(NAMESPACE, MODELS_DIR + cfg.id + ".json"),
                            () -> openJson(buildItemModelJson(cfg)));
                    if (n > 1) {
                        for (int i = 0; i < n; i++) {
                            final int idx = i;
                            output.accept(new ResourceLocation(NAMESPACE, MODELS_DIR + cfg.id + "_frame_" + idx + ".json"),
                                    () -> openJson(buildFrameModelJson(cfg, idx)));
                        }
                    }
                }
            }
            // 枚举本包提供的贴图（供 atlas directory source 查询；与 getResource 一致从 classpath 取）
            if (pathMatches(path, TEXTURES_DIR.substring(0, TEXTURES_DIR.length() - 1))) {
                Set<String> frames = new LinkedHashSet<>();
                for (CharmConfig cfg : ConfigLoader.getCharmConfigs()) frames.addAll(cfg.textureFrames);
                for (String frame : frames) {
                    String resPath = "assets/" + NAMESPACE + "/" + TEXTURES_DIR + frame + ".png";
                    if (CharmModelDataPack.class.getClassLoader().getResource(resPath) != null) {
                        output.accept(new ResourceLocation(NAMESPACE, TEXTURES_DIR + frame + ".png"),
                                () -> openClasspathResource(resPath));
                    }
                }
            }
        }

        /** 从 mod jar classpath 读取贴图：外部覆盖目录存在同名文件时返回 null（让 BOTTOM 的
         *  ExternalTexturePackFinder 提供外部版本，保留用户手绘覆盖能力）；否则从 classpath 读 jar 内置 PNG。 */
        private static IoSupplier<InputStream> getTextureSupplier(String path) {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (EXTERNAL_TEXTURE_DIR != null && Files.isDirectory(EXTERNAL_TEXTURE_DIR)
                    && Files.isRegularFile(EXTERNAL_TEXTURE_DIR.resolve(filename))) {
                return null;
            }
            String resPath = "assets/" + NAMESPACE + "/" + path;
            if (CharmModelDataPack.class.getClassLoader().getResource(resPath) != null) {
                return () -> openClasspathResource(resPath);
            }
            return null;
        }

        private static InputStream openClasspathResource(String resPath) {
            try {
                InputStream is = CharmModelDataPack.class.getClassLoader().getResourceAsStream(resPath);
                return is != null ? is : new ByteArrayInputStream(new byte[0]);
            } catch (Exception e) {
                return new ByteArrayInputStream(new byte[0]);
            }
        }

        private static boolean pathMatches(String queried, String mine) {
            return queried.equals(mine) || queried.startsWith(mine + "/") || mine.startsWith(queried);
        }

        private static InputStream openJson(String json) {
            return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Collections.singleton(NAMESPACE) : Collections.emptySet();
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            return null;
        }

        @Override
        public String packId() {
            return "Damage Overhaul Charm Models";
        }

        @Override
        public void close() {}

        @Override
        public boolean isBuiltin() { return false; }
    }
}
