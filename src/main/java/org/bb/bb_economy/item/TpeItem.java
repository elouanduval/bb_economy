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

public class TpeItem extends BlockItem {

    public static final String TAG_COMPANY_ID = "CompanyId";
    public static final String TAG_COMPANY_ACCOUNT = "CompanyAccount";

    public TpeItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static void setCompanyData(ItemStack stack, String companyId, String companyAccount) {
        stack.getOrCreateTag().putString(TAG_COMPANY_ID, companyId);
        stack.getOrCreateTag().putString(TAG_COMPANY_ACCOUNT, companyAccount);
    }

    public static String getCompanyId(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) {
            return "";
        }
        return stack.getTag().getString(TAG_COMPANY_ID);
    }

    public static String getCompanyAccount(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) {
            return "";
        }
        return stack.getTag().getString(TAG_COMPANY_ACCOUNT);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        String companyId = getCompanyId(stack);
        if (!companyId.isBlank()) {
            tooltip.add(Component.literal("Entreprise : " + companyId).withStyle(ChatFormatting.GRAY));
        }

        String companyAccount = getCompanyAccount(stack);
        if (!companyAccount.isBlank()) {
            tooltip.add(Component.literal("Compte : " + companyAccount).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
