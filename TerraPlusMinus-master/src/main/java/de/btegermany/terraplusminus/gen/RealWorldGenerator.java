package de.btegermany.terraplusminus.gen;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.tree.TreePopulator;
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

    @Getter
    private final EarthGeneratorSettings settings;
    @Getter
    private final int yOffset;
    private Location spawnLocation = null;

    private final LoadingCache<ChunkPos, CompletableFuture<CachedChunkData>> cache;
    private final CustomBiomeProvider customBiomeProvider;

    private final Material surfaceMaterial;
    private final Map<String, Material> materialMapping;

    // Licznik aktywnych zapytań HTTP do API
    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    private static final Random randomDelay = new Random();

    private static final Set<Material> GRASS_LIKE_MATERIALS = Set.of(
            GRASS_BLOCK,
            DIRT_PATH,
            FARMLAND,
            MYCELIUM,
            SNOW
    );

    public RealWorldGenerator(int yOffset) {
        Http.configChanged();

        EarthGeneratorSettings settings = EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);

        GeographicProjection projection = new OffsetProjectionTransform(
                settings.projection(),
                Terraplusminus.config.getInt("terrain_offset.x"),
                Terraplusminus.config.getInt("terrain_offset.z")
        );

        if (yOffset == 0) {
            this.yOffset = Terraplusminus.config.getInt("terrain_offset.y");
        } else {
            this.yOffset = yOffset;
        }

        this.settings = settings.withProjection(projection);
        this.customBiomeProvider = new CustomBiomeProvider(projection);

        // Zoptymalizowany Cache z obsługą softValues (ratuje RAM)
        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(5L, TimeUnit.MINUTES)
                .softValues()
                .build(new ChunkDataLoader(this.settings));

        this.surfaceMaterial = ConfigurationHelper.getMaterial(Terraplusminus.config, "surface_material", GRASS_BLOCK);
        this.materialMapping = Map.of(
                "minecraft:bricks", ConfigurationHelper.getMaterial(Terraplusminus.config, "building_outlines_material", BRICKS),
                "minecraft:gray_concrete", ConfigurationHelper.getMaterial(Terraplusminus.config, "road_material", GRAY_CONCRETE_POWDER),
                "minecraft:dirt_path", ConfigurationHelper.getMaterial(Terraplusminus.config, "path_material", MOSS_BLOCK)
        );
    }

    private CachedChunkData getTerraChunkData(int chunkX, int chunkZ) {
        try {
            // MECHANIZM ANTY-BAN (Pacing):
            // Jeśli mamy więcej niż 4 aktywne zapytania (częste przy //regen),
            // wstrzymujemy wątek na chwilę, by "rozrzedzić" ruch do API.
            if (activeRequests.get() >= 4) {
                Thread.sleep(100 + randomDelay.nextInt(150));
            }

            activeRequests.incrementAndGet();

            // Próbujemy pobrać dane (zwiększony timeout do 6s dla stabilności przy obciążeniu)
            return this.cache.getUnchecked(new ChunkPos(chunkX, chunkZ)).get(6, TimeUnit.SECONDS);

        } catch (Exception e) {
            this.cache.invalidate(new ChunkPos(chunkX, chunkZ));
            ChunkStatusCache.markAsFailed(chunkX, chunkZ);
            return null;
        } finally {
            // Zawsze zmniejszamy licznik, nawet jeśli wystąpił błąd
            activeRequests.decrementAndGet();
        }
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);

        if (terraData == null) {
            return;
        }

        int minWorldY = worldInfo.getMinHeight();
        int maxWorldY = worldInfo.getMaxHeight();

        int minSurfaceCubeY = blockToCube(minWorldY - this.yOffset);
        int maxWorldCubeY = blockToCube(maxWorldY - this.yOffset);

        if (terraData.aboveSurface(minSurfaceCubeY)) {
            return;
        }

        while (minSurfaceCubeY < maxWorldCubeY && terraData.belowSurface(minSurfaceCubeY)) {
            minSurfaceCubeY++;
        }

        if (minSurfaceCubeY >= maxWorldCubeY) {
            chunkData.setRegion(0, minWorldY, 0, 16, maxWorldY, 16, STONE);
            return;
        } else {
            chunkData.setRegion(0, minWorldY, 0, 16, cubeToMinBlock(minSurfaceCubeY), 16, STONE);
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int groundHeight = min(terraData.groundHeight(x, z) + this.yOffset, maxWorldY - 1);
                int waterHeight = min(terraData.waterHeight(x, z) + this.yOffset, maxWorldY - 1);
                chunkData.setRegion(x, minWorldY, z, x + 1, groundHeight + 1, z + 1, STONE);
                chunkData.setRegion(x, groundHeight + 1, z, x + 1, waterHeight + 1, z + 1, WATER);
            }
        }
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);
        if (terraData == null) return;

        final int minWorldY = worldInfo.getMinHeight();
        final int maxWorldY = worldInfo.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int groundY = terraData.groundHeight(x, z) + this.yOffset;
                int startMountainHeight = random.nextInt(7500, 7520);

                if (groundY < minWorldY || groundY >= maxWorldY) continue;

                Material material;
                BlockState state = terraData.surfaceBlock(x, z);

                if (state != null) {
                    material = this.materialMapping.get(state.getBlock().toString());
                    if (material == null) {
                        material = toBukkitBlockData(state).getMaterial();
                    }
                } else if (groundY >= startMountainHeight) {
                    material = STONE;
                } else {
                    Biome biome = chunkData.getBiome(x, groundY, z);
                    if (biome == DESERT) material = Material.SAND;
                    else if (biome == SNOWY_SLOPES || biome == SNOWY_PLAINS || biome == FROZEN_PEAKS) material = SNOW_BLOCK;
                    else material = this.surfaceMaterial;
                }

                boolean isUnderWater = groundY + 1 >= maxWorldY || chunkData.getBlockData(x, groundY + 1, z).getMaterial().equals(WATER);
                if (isUnderWater && GRASS_LIKE_MATERIALS.contains(material)) {
                    material = DIRT;
                }
                chunkData.setBlock(x, groundY, z, material);
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return this.customBiomeProvider;
    }

    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData chunkData) {}
    public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData chunkData) {}

    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        int chunkX = blockToCube(x);
        int chunkZ = blockToCube(z);
        x -= cubeToMinBlock(chunkX);
        z -= cubeToMinBlock(chunkZ);
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);

        if (terraData == null) return worldInfo.getMinHeight();

        return switch (heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> terraData.groundHeight(x, z) + this.yOffset;
            default -> terraData.surfaceHeight(x, z) + this.yOffset;
        };
    }

    public boolean canSpawn(@NotNull World world, int x, int z) {
        Block highest = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);
        return switch (world.getEnvironment()) {
            case NETHER -> true;
            case THE_END -> highest.getType() != Material.AIR && highest.getType() != WATER;
            default -> highest.getType() == Material.SAND || highest.getType() == Material.GRAVEL;
        };
    }

    @NotNull
    public List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return Collections.singletonList(new TreePopulator(customBiomeProvider, yOffset));
    }

    @Nullable
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
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