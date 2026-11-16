package org.bb.bb_economy.blocks;

import org.bb.bb_economy.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.AtmScreenHandler;


public class AtmBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public AtmBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }
            // Lu sur le thread principal, avant le premier appel base.
            BankManager.HandCards cards = BankManager.cardsInHand(player);
            if (cards.isEmpty()) {
                player.displayClientMessage(Component.translatable(Msg.PREFIX + "atm.hold_card"), true);
                return InteractionResult.SUCCESS;
            }
            // La carte doit appartenir au joueur (profil actif). Le patron d'une ou plusieurs entreprises peut
            // ensuite choisir leur compte a l'ecran (voir AtmSelectAccountPacket).
            BankManager.resolveAtmSession(serverPlayer, cards, session -> {
                if (session.isEmpty()) {
                    player.displayClientMessage(Component.translatable(Msg.PREFIX + "atm.card_not_yours"), true);
                    return;
                }
                AtmScreenHandler.open(player, pos, session.get());
            });
        }
        return InteractionResult.SUCCESS;
    }
}
