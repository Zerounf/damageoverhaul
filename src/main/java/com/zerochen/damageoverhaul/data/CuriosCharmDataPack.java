package com.zerochen.damageoverhaul.data;

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
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 动态 Curios 饰品数据包：以内存 SERVER_DATA 包形式注入两份数据，
 * 使 charms.json 生成的饰品可在 charm 槽装备：
 *   1) data/curios/tags/items/charm.json —— 把所有动态饰品物品加入 curios:charm 物品标签
 *   2) data/damageoverhaul/curios/entities/player.json —— 给玩家追加 charm 槽
 * Curios 仅自带槽位"类型"定义（charm.json 在其 JAR 内），但不给任何实体分配槽位、
 * 也不预置任何物品标签；二者均需本模组提供。两份数据均为 replace=false，对整合包既有
 * 配置是纯增量合并（CuriosEntityManager.apply 中 replace=false 走 putAll 累加），
 * 若整合包已分配 charm 槽则幂等无害。
 *
 * 饰品物品 ID 是动态的（由 charms.json 决定，启动时注册），无法写成静态资源文件，
 * 故用内存 PackResources 在 reload 时按当前配置即时生成标签 JSON。
 */
@Mod.EventBusSubscriber(modid = DamageOverhaulMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class CuriosCharmDataPack {

    private static final String PACK_ID = "damageoverhaul_curios_charm_data";
    private static final String TAG_PATH = "tags/items/charm.json";
    private static final String ENTITY_PATH = "curios/entities/player.json";
    private static final String ENTITY_NAMESPACE = "damageoverhaul";

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        event.addRepositorySource(new CuriosDataRepositorySource());
        DamageOverhaulMod.LOGGER.info("[CuriosData] 注入动态饰品 Curios 数据包（charm 标签 + 玩家槽位）");
    }

    /** 生成 curios:charm 标签 JSON：列出所有动态饰品物品 ID（damageoverhaul:<charm_id>）。 */
    private static String buildCharmTagJson() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("replace", false);
            JsonArray values = new JsonArray();
            List<CharmConfig> charms = ConfigLoader.getInitialCharms();
            for (CharmConfig c : charms) {
                values.add("damageoverhaul:" + c.id);
            }
            obj.add("values", values);
            return new Gson().toJson(obj);
        } catch (Throwable t) {
            DamageOverhaulMod.LOGGER.warn("[CuriosData] 生成 charm 标签失败，返回空标签: {}", t.getMessage());
            return "{\"replace\": false, \"values\": []}";
        }
    }

    /** 玩家实体槽位分配：追加 charm 槽（replace=false 纯增量，与整合包其他槽位合并）。 */
    private static String buildEntityJson() {
        return "{\"replace\": false, \"entities\": [\"minecraft:player\"], \"slots\": [\"charm\"]}";
    }

    public static class CuriosDataRepositorySource implements RepositorySource {
        @Override
        public void loadPacks(Consumer<Pack> packConsumer) {
            Pack.Info info = new Pack.Info(
                    Component.literal("Damage Overhaul 饰品槽位数据"),
                    15,
                    FeatureFlagSet.of()
            );
            Pack pack = Pack.create(
                    PACK_ID,
                    Component.literal("Damage Overhaul 饰品槽位数据"),
                    true,                                       // required：始终加载，保证标签可用
                    packId -> new CuriosCharmDataResources(),
                    info,
                    PackType.SERVER_DATA,
                    Pack.Position.TOP,                         // 高优先级，replace=false 增量项不被后续 replace=true 覆盖
                    false,
                    PackSource.BUILT_IN
            );
            packConsumer.accept(pack);
        }
    }

    public static class CuriosCharmDataResources implements PackResources {

        @Nullable
        @Override
        public IoSupplier<InputStream> getRootResource(String... paths) {
            return null;
        }

        @Nullable
        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.SERVER_DATA) return null;
            String ns = location.getNamespace();
            String path = location.getPath();
            if ("curios".equals(ns) && TAG_PATH.equals(path)) {
                return () -> openJson(buildCharmTagJson());
            }
            if (ENTITY_NAMESPACE.equals(ns) && ENTITY_PATH.equals(path)) {
                return () -> openJson(buildEntityJson());
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path,
                                  PackResources.ResourceOutput output) {
            if (type != PackType.SERVER_DATA) return;
            if ("curios".equals(namespace) && pathMatches(path, "tags/items")) {
                output.accept(new ResourceLocation("curios", TAG_PATH), () -> openJson(buildCharmTagJson()));
            } else if (ENTITY_NAMESPACE.equals(namespace) && pathMatches(path, "curios/entities")) {
                output.accept(new ResourceLocation(ENTITY_NAMESPACE, ENTITY_PATH), () -> openJson(buildEntityJson()));
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
            if (type != PackType.SERVER_DATA) return Collections.emptySet();
            return Set.of("curios", ENTITY_NAMESPACE);
        }

        @Nullable
        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            return null;
        }

        @Override
        public String packId() {
            return "Damage Overhaul Curios Charm Data";
        }

        @Override
        public void close() {}

        @Override
        public boolean isBuiltin() { return false; }
    }
}
