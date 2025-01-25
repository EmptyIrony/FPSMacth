package com.phasetranscrystal.fpsmatch.core.shop.functional;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.save.ISavedData;
import com.phasetranscrystal.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public record ChangeShopItemModule(ItemStack defaultItem, int defaultCost, ItemStack changedItem, int changedCost) implements ListenerModule , ISavedData<ChangeShopItemModule> {

    public static final Codec<ChangeShopItemModule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.CODEC.fieldOf("defaultItem").forGetter(ChangeShopItemModule::defaultItem),
            Codec.INT.fieldOf("defaultCost").forGetter(ChangeShopItemModule::defaultCost),
            ItemStack.CODEC.fieldOf("changedItem").forGetter(ChangeShopItemModule::changedItem),
            Codec.INT.fieldOf("changedCost").forGetter(ChangeShopItemModule::changedCost)
    ).apply(instance, ChangeShopItemModule::new));

    @Override
    public Codec<ChangeShopItemModule> codec() {
        return CODEC;
    }

    public void read() {
        FPSMatch.listenerModuleManager.addListenerType(this);
    }

    @Override
    public void handle(ShopSlotChangeEvent event) {
        if(event.shopSlot.getBoughtCount() > 0 && !event.shopSlot.returningChecker.test(changedItem)) return;
        if (event.flag > 0) {
            event.shopSlot.itemSupplier = changedItem::copy;
            event.shopSlot.setCost(changedCost);
        } else if (event.flag < 0) {
            event.shopSlot.returnItem(event.player);
            event.addMoney(event.shopSlot.getCost());
            event.shopSlot.itemSupplier = defaultItem::copy;
            event.shopSlot.setCost(defaultCost);
        }
    }

    @Override
    public String getName() {
        String name;
        if(this.changedItem.getItem() instanceof IGun iGun){
            name = iGun.getGunId(this.changedItem).toString().replace(":","_");
        }else{
            name = BuiltInRegistries.ITEM.getKey(this.defaultItem.getItem()).toString().replace(":","_");
        }
        return "changeItem_"+ name;
    }

    @Override
    public int getPriority() {
        return 1;
    }

}
