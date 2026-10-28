package com.ultracraft.net;

import com.ultracraft.UltraCraft;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** The mod's Forge channel; message types are registered on both sides, handlers attached per side. */
public final class Net {
	private static final String VERSION = "1";
	private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new Identifier(UltraCraft.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
	private static final Map<Class<?>, BiConsumer<Object, ServerPlayerEntity>> serverHandlers = new HashMap<>();
	private static final Map<Class<?>, Consumer<Object>> clientHandlers = new HashMap<>();
	private static int nextId;

	private Net() {}

	public static void register() {
		toServerType(FirePayload.class, FirePayload::write, FirePayload::read);
		toClientType(BeamPayload.class, BeamPayload::write, BeamPayload::read);
		toClientType(HitPayload.class, HitPayload::write, HitPayload::read);
		toServerType(PointsPayload.class, PointsPayload::write, PointsPayload::read);
		toClientType(PointsSyncPayload.class, PointsSyncPayload::write, PointsSyncPayload::read);
		toClientType(ParryWarnPayload.class, ParryWarnPayload::write, ParryWarnPayload::read);
		toClientType(ExplosionPayload.class, ExplosionPayload::write, ExplosionPayload::read);
		toClientType(LaunchPayload.class, LaunchPayload::write, LaunchPayload::read);
		toClientType(GasolinePayload.class, GasolinePayload::write, GasolinePayload::read);
		toServerType(ShopPayload.class, ShopPayload::write, ShopPayload::read);
		toClientType(OwnedPayload.class, OwnedPayload::write, OwnedPayload::read);
		toServerType(HandPayload.class, HandPayload::write, HandPayload::read);
	}

	private static <T> void toServerType(Class<T> type, BiConsumer<T, PacketByteBuf> write, Function<PacketByteBuf, T> read) {
		CHANNEL.registerMessage(nextId++, type, write, read, (T msg, Supplier<NetworkEvent.Context> ctx) -> {
			NetworkEvent.Context c = ctx.get();
			ServerPlayerEntity sender = c.getSender();
			BiConsumer<Object, ServerPlayerEntity> h = serverHandlers.get(type);
			if (sender != null && msg instanceof HandPayload hp) c.enqueueWork(() -> { if (hp.off()) OFFHAND.add(sender.getUuid()); else OFFHAND.remove(sender.getUuid()); });
			else if (sender != null && h != null) c.enqueueWork(() -> {
				// an off-hand weapon's packet: handled with the hands swapped (the handlers read the main hand)
				boolean swap = OFFHAND.contains(sender.getUuid());
				if (swap) swapHands(sender);
				try {
					h.accept(msg, sender);
				} finally {
					if (swap) swapHands(sender);
				}
			});
			c.setPacketHandled(true);
		}, Optional.of(NetworkDirection.PLAY_TO_SERVER));
	}

	private static <T> void toClientType(Class<T> type, BiConsumer<T, PacketByteBuf> write, Function<PacketByteBuf, T> read) {
		CHANNEL.registerMessage(nextId++, type, write, read, (T msg, Supplier<NetworkEvent.Context> ctx) -> {
			NetworkEvent.Context c = ctx.get();
			Consumer<Object> h = clientHandlers.get(type);
			if (h != null) c.enqueueWork(() -> h.accept(msg));
			c.setPacketHandled(true);
		}, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
	}

	@SuppressWarnings("unchecked")
	public static <T> void onServer(Class<T> type, BiConsumer<T, ServerPlayerEntity> handler) {
		serverHandlers.put(type, (BiConsumer<Object, ServerPlayerEntity>) handler);
	}

	@SuppressWarnings("unchecked")
	public static <T> void onClient(Class<T> type, Consumer<T> handler) {
		clientHandlers.put(type, (Consumer<Object>) handler);
	}

	/** Server: the players whose next packets come from their off-hand weapon (HandPayload). */
	private static final java.util.Set<java.util.UUID> OFFHAND = new java.util.HashSet<>();

	/** Server: whether the packet being handled now came from the player's off-hand weapon. */
	public static boolean offhand(ServerPlayerEntity p) {
		return OFFHAND.contains(p.getUuid());
	}

	/** A player (re)joining starts on the main hand. */
	public static void clearOffhand(ServerPlayerEntity p) {
		OFFHAND.remove(p.getUuid());
	}

	/** The selected hotbar stack and the off-hand stack trade places (the stacks themselves, no equip events or sounds). */
	public static void swapHands(net.minecraft.entity.player.PlayerEntity p) {
		var inv = p.getInventory();
		int sel = inv.selectedSlot;
		if (sel < 0 || sel >= inv.main.size()) return;
		net.minecraft.item.ItemStack m = inv.main.get(sel);
		inv.main.set(sel, inv.offHand.get(0));
		inv.offHand.set(0, m);
	}

	/** Client: while on, packets are tagged as the off-hand weapon's (a HandPayload before the first, one after the last). */
	private static boolean offhandMode, offhandSent;

	public static void beginOffhand() {
		offhandMode = true;
		offhandSent = false;
	}

	public static void endOffhand() {
		if (offhandSent) CHANNEL.sendToServer(new HandPayload(false));
		offhandMode = false;
		offhandSent = false;
	}

	public static void toServer(Object msg) {
		if (offhandMode && !offhandSent) {
			CHANNEL.sendToServer(new HandPayload(true));
			offhandSent = true;
		}
		CHANNEL.sendToServer(msg);
	}

	public static void toPlayer(ServerPlayerEntity player, Object msg) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
	}

	/** Everyone tracking a point (incl. the player themselves) in a world. */
	public static void toNear(ServerWorld world, Vec3d pos, Object msg) {
		CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(pos.x, pos.y, pos.z, 128, world.getRegistryKey())), msg);
	}

	public static void writeVec(PacketByteBuf buf, Vec3d v) {
		buf.writeDouble(v.x);
		buf.writeDouble(v.y);
		buf.writeDouble(v.z);
	}

	public static Vec3d readVec(PacketByteBuf buf) {
		return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}
}
