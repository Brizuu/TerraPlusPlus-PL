package de.btegermany.terraplusminus.gen;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.utils.ConfigurationHelper;
import lombok.Getter;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.ChunkDataLoader;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.http.Http;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.min;
import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.blockToCube;
import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.cubeToMinBlock;
import static net.buildtheearth.terraminusminus.substitutes.TerraBukkit.toBukkitBlockData;
import static org.bukkit.Material.*;
import static org.bukkit.block.Biome.*;

public class RealWorldGenerator extends ChunkGenerator {

    @Getter private final EarthGeneratorSettings settings;
    @Getter private final int yOffset;
    private Location spawnLocation = null;

    private final LoadingCache<ChunkPos, CompletableFuture<CachedChunkData>> primaryCache;
    private final LoadingCache<ChunkPos, CachedChunkData> tickCache;

    private final CustomBiomeProvider customBiomeProvider;
    private final Material surfaceMaterial;
    private final Map<String, Material> materialMapping;

    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    private static final int MAX_CONCURRENT = 12;
    private static long globalApiLockoutUntil = 0;

    private static final Set<Material> GRASS_LIKE_MATERIALS = Set.of(
            GRASS_BLOCK, DIRT_PATH, FARMLAND, MYCELIUM, SNOW
    );

    public RealWorldGenerator(int yOffset) {
        System.setProperty("sun.net.client.defaultConnectTimeout", "2000");
        System.setProperty("sun.net.client.defaultReadTimeout", "2000");
        Http.configChanged();

        EarthGeneratorSettings settings = EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
        GeographicProjection projection = new OffsetProjectionTransform(
                settings.projection(),
                Terraplusminus.config.getInt("terrain_offset.x"),
                Terraplusminus.config.getInt("terrain_offset.z")
        );

        this.yOffset = (yOffset == 0) ? Terraplusminus.config.getInt("terrain_offset.y") : yOffset;
        this.settings = settings.withProjection(projection);
        this.customBiomeProvider = new CustomBiomeProvider(projection);

        this.primaryCache = CacheBuilder.newBuilder()
                .expireAfterAccess(5L, TimeUnit.MINUTES)
                .maximumSize(1000)
                .softValues()
                .build(new ChunkDataLoader(this.settings));

        this.tickCache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .maximumSize(512)
                .build(new CacheLoader<>() {
                    @Override
                    public CachedChunkData load(@NotNull ChunkPos pos) {
                        return fetchFromPrimary(pos, true);
                    }
                });

        this.surfaceMaterial = ConfigurationHelper.getMaterial(Terraplusminus.config, "surface_material", GRASS_BLOCK);
        this.materialMapping = Map.of(
                "minecraft:bricks", ConfigurationHelper.getMaterial(Terraplusminus.config, "building_outlines_material", BRICKS),
                "minecraft:gray_concrete", ConfigurationHelper.getMaterial(Terraplusminus.config, "road_material", GRAY_CONCRETE_POWDER),
                "minecraft:dirt_path", ConfigurationHelper.getMaterial(Terraplusminus.config, "path_material", MOSS_BLOCK)
        );
    }


    private CachedChunkData fetchFromPrimary(ChunkPos pos, boolean block) {
        long currentTime = System.currentTimeMillis();
        if (currentTime < globalApiLockoutUntil) return null;

        try {
            CompletableFuture<CachedChunkData> future = this.primaryCache.getIfPresent(pos);

            if (future == null) {
                if (activeRequests.get() >= MAX_CONCURRENT) return null;
                activeRequests.incrementAndGet();
                future = this.primaryCache.getUnchecked(pos);
                future.whenComplete((d, ex) -> activeRequests.decrementAndGet());
            }

            if (future.isDone()) {
                return future.getNow(null);
            } else if (block) {
                return future.get(2000, TimeUnit.MILLISECONDS);
            } else {
                return null;
            }

        } catch (Exception e) {
            handleApiError(e);
            return null;
        }
    }

    private void handleApiError(Exception e) {
        String msg = e.toString().toLowerCase();
        if (e.getCause() != null) msg += " " + e.getCause().toString().toLowerCase();

        if (msg.contains("429") || msg.contains("reset") || msg.contains("refused") || msg.contains("too many")) {
            globalApiLockoutUntil = System.currentTimeMillis() + 30000;
            Terraplusminus.instance.getLogger().warning("API Protected - Cooling down for 30s");
        }
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CachedChunkData terraData = null;
        try {
            terraData = tickCache.getUnchecked(new ChunkPos(chunkX, chunkZ));
        } catch (Exception ignored) {}

        int minWorldY = worldInfo.getMinHeight();
        int maxWorldY = worldInfo.getMaxHeight();

        if (terraData == null) {
            if (this.yOffset > minWorldY) {
                chunkData.setRegion(0, minWorldY, 0, 16, min(this.yOffset, maxWorldY), 16, STONE);
            }
            return;
        }

        int minSurfaceCubeY = blockToCube(minWorldY - this.yOffset);
        int maxWorldCubeY = blockToCube(maxWorldY - this.yOffset);

        if (terraData.aboveSurface(minSurfaceCubeY)) return;

        while (minSurfaceCubeY < maxWorldCubeY && terraData.belowSurface(minSurfaceCubeY)) {
            minSurfaceCubeY++;
        }

        if (minSurfaceCubeY >= maxWorldCubeY) {
            chunkData.setRegion(0, minWorldY, 0, 16, maxWorldY, 16, STONE);
        } else {
            chunkData.setRegion(0, minWorldY, 0, 16, cubeToMinBlock(minSurfaceCubeY), 16, STONE);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int groundHeight = min(terraData.groundHeight(x, z) + this.yOffset, maxWorldY - 1);
                    int waterHeight = min(terraData.waterHeight(x, z) + this.yOffset, maxWorldY - 1);
                    if (groundHeight >= minWorldY) {
                        chunkData.setRegion(x, minWorldY, z, x + 1, groundHeight + 1, z + 1, STONE);
                    }
                    if (waterHeight > groundHeight) {
                        chunkData.setRegion(x, groundHeight + 1, z, x + 1, waterHeight + 1, z + 1, WATER);
                    }
                }
            }
        }
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CachedChunkData terraData = null;
        try {
            terraData = tickCache.getUnchecked(new ChunkPos(chunkX, chunkZ));
        } catch (Exception ignored) {}

        if (terraData == null) return;

        final int minWorldY = worldInfo.getMinHeight();
        final int maxWorldY = worldInfo.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int groundY = terraData.groundHeight(x, z) + this.yOffset;
                if (groundY < minWorldY || groundY >= maxWorldY) continue;

                Material material;
                BlockState state = terraData.surfaceBlock(x, z);

                if (state != null) {
                    material = this.materialMapping.getOrDefault(state.getBlock().toString(), toBukkitBlockData(state).getMaterial());
                } else if (groundY >= 7500) {
                    material = STONE;
                } else {
                    Biome biome = chunkData.getBiome(x, groundY, z);
                    if (biome == DESERT) material = SAND;
                    else if (biome == SNOWY_SLOPES || biome == SNOWY_PLAINS) material = SNOW_BLOCK;
                    else material = this.surfaceMaterial;
                }

                if (groundY + 1 < maxWorldY && chunkData.getType(x, groundY + 1, z) == WATER && GRASS_LIKE_MATERIALS.contains(material)) {
                    material = DIRT;
                }
                chunkData.setBlock(x, groundY, z, material);
            }
        }
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        CachedChunkData data = fetchFromPrimary(new ChunkPos(blockToCube(x), blockToCube(z)), false);

        if (data == null) return this.yOffset;

        int relX = x & 15;
        int relZ = z & 15;
        return (heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG)
                ? data.groundHeight(relX, relZ) + yOffset : data.surfaceHeight(relX, relZ) + yOffset;
    }

    @Override public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) { return this.customBiomeProvider; }
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {}
    public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {}

    @Nullable public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        if (spawnLocation == null) spawnLocation = new Location(world, 3517417, 58, -5288234);
        return spawnLocation;
    }

    public boolean shouldGenerateNoise() { return false; }
    public boolean shouldGenerateSurface() { return false; }
    public boolean shouldGenerateBedrock() { return false; }
    public boolean shouldGenerateCaves() { return false; }
    public boolean shouldGenerateDecorations() { return false; }
    public boolean shouldGenerateMobs() { return false; }
    public boolean shouldGenerateStructures() { return false; }
}