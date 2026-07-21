package org.bb.bb_economy.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

public class CardItem extends Item {

    public static final String TAG_CARD_NUMBER = "CardNumber";

    public CardItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String cardNumber = getCardNumber(stack);
        if (!cardNumber.isBlank()) {
            tooltip.add(Component.literal("Numero : " + cardNumber).withStyle(ChatFormatting.GRAY));
        }
    }

    public static String getCardNumber(ItemStack stack) {
        if (stack.hasTag() && stack.getTag() != null && stack.getTag().contains(TAG_CARD_NUMBER)) {
            return stack.getTag().getString(TAG_CARD_NUMBER);
        }
        return "";
    }
}
