package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.CompanyChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PinResponsePacket {

    /** Plafond de securite a la lecture : un patron n'a jamais autant d'entreprises. */
    private static final int MAX_COMPANIES = 64;

    final boolean success;
    final double balance;
    final String message;
    /** Entreprises dont le joueur est le patron (ATM uniquement, vide sinon) : comptes proposes en plus du sien. */
    final List<CompanyChoice> companies;

    public PinResponsePacket(boolean success, double balance, String message, List<CompanyChoice> companies) {
        this.success = success;
        this.balance = balance;
        this.message = message;
        this.companies = companies;
    }

    public PinResponsePacket(boolean success, double balance, String message) {
        this(success, balance, message, List.of());
    }

    public static void encode(PinResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeDouble(packet.balance);
        buf.writeUtf(packet.message);
        buf.writeVarInt(packet.companies.size());
        for (CompanyChoice company : packet.companies) {
            buf.writeInt(company.id());
            buf.writeUtf(company.name(), 128);
        }
    }

    public static PinResponsePacket decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        double balance = buf.readDouble();
        String message = buf.readUtf(256);
        int count = Math.min(buf.readVarInt(), MAX_COMPANIES);
        List<CompanyChoice> companies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            companies.add(new CompanyChoice(buf.readInt(), buf.readUtf(128)));
        }
        return new PinResponsePacket(success, balance, message, companies);
    }

    public static void handle(PinResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handlePinResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}
