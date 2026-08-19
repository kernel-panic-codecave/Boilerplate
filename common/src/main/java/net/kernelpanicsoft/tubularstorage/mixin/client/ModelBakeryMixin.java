package net.kernelpanicsoft.tubularstorage.mixin.client;

import net.kernelpanicsoft.tubularstorage.TubularStorage;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin {

    @Shadow
    protected abstract void registerModelAndLoadDependencies(ModelResourceLocation modelLocation, UnbakedModel model);

    @Shadow
    abstract UnbakedModel getModel(ResourceLocation modelLocation);

    @Inject(method = "<init>(Lnet/minecraft/client/color/block/BlockColors;Lnet/minecraft/util/profiling/ProfilerFiller;Ljava/util/Map;Ljava/util/Map;)V", at = {@At("RETURN")})
    private void addCustomBakedModels(BlockColors blockColors,
                                             ProfilerFiller profilerFiller,
                                             Map<ResourceLocation, BlockModel> modelResources,
                                             Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStateResources,
                                             CallbackInfo ci)
    {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(TubularStorage.MOD_ID, "gantry_head");
        UnbakedModel model = getModel(id.withPrefix("block/"));
        ModelResourceLocation modelLocation = new ModelResourceLocation(id, "standalone");
        registerModelAndLoadDependencies(modelLocation, model);
    }
}
