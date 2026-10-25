package com.zerochen.damageoverhaul.client;

import com.zerochen.damageoverhaul.DamageOverhaulMod;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 外部贴图加载器：把游戏根目录 <game>/damageoverhaul/item/ 下的 PNG 文件
 * 注入为虚拟资源包，让标准 ResourceLocation "damageoverhaul:textures/item/<name>.png"
 * 能直接命中外部文件。玩家把 PNG 拖进文件夹，按 F3+T 或 /damageoverhaul reload 即可刷新。
 *
 * 实现方式：AddPackFindersEvent 注册一个 RepositorySource，它生成一个 Pack，
 * 包装自定义 CharmFolderPackResources（实现 PackResources 接口）。
 * CharmFolderPackResources 把扁平文件 <name>.png 映射到
 * assets/damageoverhaul/textures/item/<name>.png。
 *
 * 注意：本类只在客户端注入 CLIENT_RESOURCES，服务端不读贴图。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ExternalTexturePackFinder {

    private static final String EXTERNAL_DIR = "damageoverhaul/item";
    private static final String MAPPED_NAMESPACE = "damageoverhaul";
    private static final String MAPPED_PREFIX = "textures/item/";

    @org.jetbrains.annotations.Nullable
    private static Path externalDir;

    static {
        try {
            externalDir = FMLPaths.GAMEDIR.get().resolve(EXTERNAL_DIR);
        } catch (Throwable ignored) {
            externalDir = null;
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (externalDir == null || !Files.isDirectory(externalDir)) return;
        // 检查目录下是否有 PNG
        try (Stream<Path> s = Files.list(externalDir)) {
            boolean hasPng = s.anyMatch(p -> p.toString().endsWith(".png"));
            if (!hasPng) return;
        } catch (IOException e) {
            return;
        }

        event.addRepositorySource(new CharmFolderRepositorySource(externalDir));
        DamageOverhaulMod.LOGGER.info("[CharmTextures] 已注入外部贴图目录: {}", externalDir);
    }

    /** RepositorySource：在游戏资源包列表里追加我们的虚拟包 */
    public static class CharmFolderRepositorySource implements RepositorySource {
        private final Path dir;

        public CharmFolderRepositorySource(Path dir) { this.dir = dir; }

        @Override
        public void loadPacks(java.util.function.Consumer<Pack> packConsumer) {
            // 1.20.1 官方映射下 Pack.create 只有 9 参重载（含 Info），需手动构造 Pack.Info record
            Pack.Info info = new Pack.Info(
                    net.minecraft.network.chat.Component.literal("Damage Overhaul 饰品外部贴图"),
                    15,                                                  // pack_format (1.20.1 client resources)
                    net.minecraft.world.flag.FeatureFlagSet.of()        // 无 feature flags（空集合）
            );
            Pack pack = Pack.create(
                    "damageoverhaul_charm_external",
                    net.minecraft.network.chat.Component.literal("Damage Overhaul 饰品外部贴图"),
                    false,                                              // required
                    packId -> new CharmFolderPackResources(dir),        // ResourcesSupplier
                    info,                                               // Pack.Info
                    PackType.CLIENT_RESOURCES,                          // packType
                    Pack.Position.BOTTOM,
                    false,                                              // hidden
                    PackSource.BUILT_IN
            );
            packConsumer.accept(pack);
        }
    }

    /** 自定义 PackResources：把文件夹下扁平 PNG 映射到标准资源路径 */
    public static class CharmFolderPackResources implements PackResources {
        private final Path dir;

        public CharmFolderPackResources(Path dir) { this.dir = dir; }

        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... paths) {
            // 仅允许 pack.mcmeta 查询返回 null（让系统知道这是简化包）
            return null;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES) return null;
            String path = location.getPath();
            // 只匹配 textures/item/charm/<name>.png
            if (!path.startsWith(MAPPED_PREFIX) || !path.endsWith(".png")) return null;
            String filename = path.substring(MAPPED_PREFIX.length());
            Path file = dir.resolve(filename);
            if (!Files.isRegularFile(file)) return null;
            return () -> Files.newInputStream(file);
        }

        @Override
        public void listResources(PackType type, String namespace, String path,
                                  PackResources.ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !MAPPED_NAMESPACE.equals(namespace)) return;
            if (!path.equals(MAPPED_PREFIX) && !path.startsWith(MAPPED_PREFIX) && !MAPPED_PREFIX.startsWith(path)) return;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".png"))
                 .forEach(p -> {
                     String filename = p.getFileName().toString();
                     String resPath = MAPPED_PREFIX + filename;
                     output.accept(new ResourceLocation(MAPPED_NAMESPACE, resPath), () -> {
                         try { return Files.newInputStream(p); }
                         catch (IOException e) { throw new RuntimeException(e); }
                     });
                 });
            } catch (IOException ignored) {}
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES
                    ? Collections.singleton(MAPPED_NAMESPACE)
                    : Collections.emptySet();
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            return null;
        }

        @Override
        public String packId() {
            return "Damage Overhaul Charm External Textures";
        }

        @Override
        public void close() {}

        @Override
        public boolean isBuiltin() { return false; }
    }
}
