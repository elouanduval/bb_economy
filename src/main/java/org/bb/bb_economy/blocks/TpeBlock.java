package org.bb.bb_economy.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.item.TpeItem;
import org.jetbrains.annotations.Nullable;

public class TpeBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public TpeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TpeBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof TpeBlockEntity tpeBlockEntity) {
            tpeBlockEntity.setCompanyData(TpeItem.getCompanyId(stack), TpeItem.getCompanyAccount(stack));
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof TpeBlockEntity tpeBlockEntity)) {
                return InteractionResult.SUCCESS;
            }

            if (tpeBlockEntity.getCompanyId().isBlank()) {
                player.displayClientMessage(Component.literal("Ce TPE n'est lie a aucune entreprise."), true);
                return InteractionResult.SUCCESS;
            }

            if (!BankManager.playerWorksForCompany(player, tpeBlockEntity.getCompanyId())) {
                if (!BankManager.hasOwnedCardInHand(player)) {
                    player.displayClientMessage(Component.literal("Vous devez cliquer avec votre carte bancaire en main pour utiliser ce TPE."), true);
                    return InteractionResult.SUCCESS;
                }

                TpeScreenHandler.open(player, pos, tpeBlockEntity, TpeScreenHandler.AccessMode.BUYER);
                return InteractionResult.SUCCESS;
            }

            TpeScreenHandler.open(player, pos, tpeBlockEntity, TpeScreenHandler.AccessMode.SELLER);
        }
        return InteractionResult.SUCCESS;
    }
}
