package org.bb.bb_economy.blocks.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bb.bb_economy.init.ModBlockEntities;
import org.bb.bb_economy.item.TpeItem;

import java.math.BigDecimal;

public class TpeBlockEntity extends BlockEntity {

    private String companyId = "";
    private String companyAccount = "";
    private BigDecimal pendingAmount = BigDecimal.ZERO;

    public TpeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TPE_BLOCK_ENTITY.get(), pos, state);
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId == null ? "" : companyId;
        setChanged();
    }

    public String getCompanyAccount() {
        return companyAccount;
    }

    public void setCompanyAccount(String companyAccount) {
        this.companyAccount = companyAccount == null ? "" : companyAccount;
        setChanged();
    }

    public void setCompanyData(String companyId, String companyAccount) {
        this.companyId = companyId == null ? "" : companyId;
        this.companyAccount = companyAccount == null ? "" : companyAccount;
        setChanged();
    }

    public BigDecimal getPendingAmount() {
        return pendingAmount;
    }

    public void setPendingAmount(BigDecimal pendingAmount) {
        this.pendingAmount = pendingAmount == null ? BigDecimal.ZERO : pendingAmount;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(TpeItem.TAG_COMPANY_ID, companyId);
        tag.putString(TpeItem.TAG_COMPANY_ACCOUNT, companyAccount);
        tag.putString("pending_amount", pendingAmount.toPlainString());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        companyId = tag.getString(TpeItem.TAG_COMPANY_ID);
        companyAccount = tag.getString(TpeItem.TAG_COMPANY_ACCOUNT);
        String amount = tag.getString("pending_amount");
        pendingAmount = amount.isBlank() ? BigDecimal.ZERO : new BigDecimal(amount);
    }
}
