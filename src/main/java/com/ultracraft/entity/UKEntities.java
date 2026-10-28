package com.ultracraft.entity;

import com.ultracraft.UltraCraft;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.RegistryKeys;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class UKEntities {
	public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(RegistryKeys.ENTITY_TYPE, UltraCraft.MOD_ID);

	/** The Marksman's Coin: a 1 u sphere collider in the game, a small flat disc to look at. */
	public static final RegistryObject<EntityType<CoinEntity>> COIN = ENTITIES.register("coin", () ->
		EntityType.Builder.<CoinEntity>create(CoinEntity::new, SpawnGroup.MISC).setDimensions(0.25f, 0.25f).maxTrackingRange(8).trackingTickInterval(1).build("coin"));

	/** Every other player projectile (pellets, nails, rockets, cores, cannonballs, harpoons, gasoline, zapper). */
	public static final RegistryObject<EntityType<UKProjectileEntity>> PROJECTILE = ENTITIES.register("projectile", () ->
		EntityType.Builder.<UKProjectileEntity>create(UKProjectileEntity::new, SpawnGroup.MISC).setDimensions(0.25f, 0.25f).maxTrackingRange(10).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true).build("projectile"));

	private UKEntities() {}

	public static void register(IEventBus modBus) {
		ENTITIES.register(modBus);
	}
}
