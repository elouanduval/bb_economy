package org.bb.bb_economy.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Boutique automatique a poser : elle porte l'id de l'entreprise (table de l'autre mod) dont elle utilise le compte. */
public class ShopItem extends BlockItem {

    public static final String TAG_COMPANY_ID = "CompanyId";

    public ShopItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static void setCompanyId(ItemStack stack, String companyId) {
        stack.getOrCreateTag().putString(TAG_COMPANY_ID, companyId);
    }

    public static String getCompanyId(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) {
            return "";
        }
        return stack.getTag().getString(TAG_COMPANY_ID);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String companyId = getCompanyId(stack);
        if (!companyId.isBlank()) {
            tooltip.add(Component.literal("Entreprise (id) : " + companyId).withStyle(ChatFormatting.GRAY));
        }
    }
}
