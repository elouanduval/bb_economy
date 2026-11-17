package org.bb.bb_economy.blocks;

import org.bb.bb_economy.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.bb.bb_economy.blocks.entity.ShopBlockEntity;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.ShopManager;
import org.bb.bb_economy.gui.ShopScreenHandler;
import org.bb.bb_economy.item.ShopItem;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalInt;

/**
 * Boutique automatique : sans vendeur, le joueur y vend et y achete les objets de l'entreprise liee, au compte
 * de cette entreprise. Les offres sont celles du metier de l'entreprise (voir ShopManager).
 */
public class ShopBlock extends BaseEntityBlock {

    /** Face avant de l'etal (comptoir), tournee vers le joueur qui le pose. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public ShopBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
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
        return new ShopBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof ShopBlockEntity shop) {
            shop.setCompanyId(ShopItem.getCompanyId(stack));
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ShopBlockEntity shop)) {
            return InteractionResult.SUCCESS;
        }
        OptionalInt companyId = BankManager.parseCompanyId(shop.getCompanyId());
        if (companyId.isEmpty()) {
            player.displayClientMessage(Component.translatable(Msg.PREFIX + "shop.no_company"), true);
            return InteractionResult.SUCCESS;
        }

        // Les offres sont lues en base, hors du thread principal.
        ShopManager.loadShop(serverPlayer, companyId.getAsInt(), view -> {
            if (view.isEmpty()) {
                player.displayClientMessage(Component.translatable(Msg.PREFIX + "shop.unavailable"), true);
                return;
            }
            if (level.getBlockEntity(pos) != shop) {
                return; // le bloc a ete casse ou remplace pendant la lecture
            }
            ShopScreenHandler.open(player, pos, companyId.getAsInt(), view.get());
        });
        return InteractionResult.SUCCESS;
    }
}
