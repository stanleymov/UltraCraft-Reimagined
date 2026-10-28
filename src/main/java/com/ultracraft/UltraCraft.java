package com.ultracraft;

import com.ultracraft.entity.UKEntities;
import com.ultracraft.item.UKItems;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.WeaponServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** UltraCraft: V1 (ULTRAKILL) inside Minecraft Forge 1.20.1. */
@Mod(UltraCraft.MOD_ID)
public final class UltraCraft {
	public static final String MOD_ID = "ultracraft";
	public static final Logger LOG = LoggerFactory.getLogger("UltraCraft");

	public UltraCraft() {
		var modBus = FMLJavaModLoadingContext.get().getModEventBus();
		net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, UkConfig.SPEC);
		UKItems.register(modBus);
		UKEntities.register(modBus);
		com.ultracraft.block.UKBlocks.register(modBus);
		modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
			Net.register();
			WeaponServer.init();
		}));
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.ultracraft.client.UltraCraftClient.init(modBus));
		LOG.info("UltraCraft loading");
	}
}
