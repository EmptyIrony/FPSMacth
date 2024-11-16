package com.phasetranscrystal.fpsmatch.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.phasetranscrystal.fpsmatch.entity.CompositionC4Entity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

public class C4Renderer implements EntityRendererProvider<CompositionC4Entity> {

    @Override
    public @NotNull EntityRenderer<CompositionC4Entity> create(Context pContext) {
        return new EntityRenderer<>(pContext) {
            ItemEntity item = null  ;
            ItemEntityRenderer itemRender = null;
            @Override
            public @NotNull ResourceLocation getTextureLocation(CompositionC4Entity pEntity) {
                return TextureAtlas.LOCATION_BLOCKS;
            }

            @Override
            public void render(CompositionC4Entity pEntity, float pEntityYaw, float pPartialTicks, PoseStack pPoseStack, MultiBufferSource pBuffer, int pPackedLight) {
                pPoseStack.pushPose();
                pPoseStack.translate(0.0F, -0.25F, 0.0F);
                if(item == null){
                    item = new ItemEntity(pEntity.level(),pEntity.getX(),pEntity.getY(),pEntity.getZ(),new ItemStack(Items.TNT));
                    itemRender = new ItemEntityRenderer(pContext);
                }
                itemRender.render(item,pEntityYaw,20,pPoseStack,pBuffer,pPackedLight);
                pPoseStack.popPose();
                super.render(pEntity, pEntityYaw, pPartialTicks, pPoseStack, pBuffer, pPackedLight);
            }

        };
    }
}
