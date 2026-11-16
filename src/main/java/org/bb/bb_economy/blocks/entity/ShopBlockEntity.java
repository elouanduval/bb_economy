package org.bb.bb_economy.blocks.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bb.bb_economy.init.ModBlockEntities;
import org.bb.bb_economy.item.ShopItem;

public class ShopBlockEntity extends BlockEntity {

    private String companyId = "";

    public ShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP_BLOCK_ENTITY.get(), pos, state);
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId == null ? "" : companyId;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(ShopItem.TAG_COMPANY_ID, companyId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        companyId = tag.getString(ShopItem.TAG_COMPANY_ID);
    }
}
