package com.phasetranscrystal.fpsmatch.net;

import com.phasetranscrystal.fpsmatch.client.CSGameShopScreen;
import com.phasetranscrystal.fpsmatch.client.ClientData;
import com.phasetranscrystal.fpsmatch.core.data.ShopData;
import com.phasetranscrystal.fpsmatch.util.RenderUtil;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ShopDataSlotS2CPacket {
    public final ShopData.ItemType type;
    public final int index;
    public final String name;
    public final ItemStack itemStack;
    public final int cost;
    public final int money;
    public ShopDataSlotS2CPacket(ShopData.ItemType type, int index, String name, ItemStack itemStack, int cost,int money){
        this.type = type;
        this.index = index;
        this.name = name;
        this.itemStack =itemStack;
        this.cost = cost;
        this.money = money;
    }

    public ShopDataSlotS2CPacket(ShopData.ShopSlot shopSlot,String name,int money){
        this.type = shopSlot.type();
        this.index = shopSlot.index();
        this.name = name;
        this.itemStack =shopSlot.itemStack();
        this.cost = shopSlot.cost();
        this.money = money;
    }

    public static void encode(ShopDataSlotS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.type.ordinal());
        buf.writeInt(packet.index);
        buf.writeUtf(packet.name);
        buf.writeItemStack(packet.itemStack, false);
        buf.writeInt(packet.cost);
        buf.writeInt(packet.money);
    }

    public static ShopDataSlotS2CPacket decode(FriendlyByteBuf buf) {
        return new ShopDataSlotS2CPacket(
                ShopData.ItemType.values()[buf.readInt()],
                buf.readInt(),
                buf.readUtf(),
                buf.readItem(),
                buf.readInt(),
                buf.readInt()
        );
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientData.currentMap = this.name;
            ShopData.ShopSlot slot = new ShopData.ShopSlot(this.index,this.type,this.itemStack,this.cost);
            if(this.itemStack.getItem() instanceof IGun iGun){
                ClientGunIndex gunIndex = TimelessAPI.getClientGunIndex(iGun.getGunId(this.itemStack)).orElse(null);
                if (gunIndex != null){
                    slot.setTexture(gunIndex.getHUDTexture());
                    CSGameShopScreen.refreshFlag = true;
                }
            }
            ClientData.clientShopData.addShopSlot(slot);
            ClientData.money = money;

            if(slot.canReturn()){
                CSGameShopScreen.shopButtons.get(type).get(index).handleBuyEvent();
            }else{
                CSGameShopScreen.shopButtons.get(type).get(index).handleReturnEvent();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
