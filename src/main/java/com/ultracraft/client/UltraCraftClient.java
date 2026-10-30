package com.ultracraft.client;

import com.ultracraft.client.hud.UkHud;
import com.ultracraft.client.style.StyleState;
import com.ultracraft.client.v1.V1;
import com.ultracraft.client.weapon.BeamRenderer;
import com.ultracraft.client.weapon.CoinRenderer;
import com.ultracraft.client.weapon.ExplosionFx;
import com.ultracraft.client.weapon.ProjectileRenderer;
import com.ultracraft.client.v1.V1Movement;
import com.ultracraft.net.ExplosionPayload;
import com.ultracraft.net.LaunchPayload;
import com.ultracraft.entity.UKEntities;
import com.ultracraft.client.weapon.HandRenderer;
import com.ultracraft.client.weapon.WeaponClient;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKItems;
import com.ultracraft.net.BeamPayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.net.PointsSyncPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class UltraCraftClient {
	private static boolean controlsChecked;
	/** ColorBlindSettings.variationColors defaults: blue, green, red (, yellow). */
	public static final int[] VARIATION_COLORS = {0x40E7FF, 0x00FF00, 0xFF0000, 0xFFFF00}; // blue: the HUD cyan (0.25, 0.91, 1), measured off the game's weapon icon

	private UltraCraftClient() {}

	public static void init(IEventBus modBus) {
		net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new UkConfigScreen(parent)));
		modBus.addListener((RegisterKeyMappingsEvent e) -> {
			for (KeyBinding k : UKKeys.ALL) e.register(k);
		});
		modBus.addListener((RegisterGuiOverlaysEvent e) ->
			e.registerBelow(VanillaGuiOverlay.DEBUG_TEXT.id(), "v1_hud", (gui, ctx, partialTick, w, h) -> UkHud.render(ctx, partialTick, w, h)));
		modBus.addListener((RegisterColorHandlersEvent.Item e) ->
			e.register((stack, layer) -> layer == 0 ? VARIATION_COLORS[com.ultracraft.item.UKWeaponItem.variation(stack) % 3] : 0xFFFFFF,
				UKItems.REVOLVER.get(), UKItems.SHOTGUN.get(), UKItems.NAILGUN.get(), UKItems.RAILCANNON.get(), UKItems.ROCKET_LAUNCHER.get()));
		modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
			e.registerEntityRenderer(UKEntities.COIN.get(), CoinRenderer::new);
			e.registerEntityRenderer(UKEntities.PROJECTILE.get(), ProjectileRenderer::new);
			e.registerBlockEntityRenderer(com.ultracraft.block.UKBlocks.GRAPPLE_ORB_ENTITY.get(), com.ultracraft.client.weapon.GrappleOrbRenderer::new);
			e.registerBlockEntityRenderer(com.ultracraft.block.UKBlocks.TERMINAL_ENTITY.get(), com.ultracraft.client.shop.TerminalRenderer::new);
		});
		modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> {
			for (var item : new net.minecraft.item.Item[] {UKItems.REVOLVER.get(), UKItems.SHOTGUN.get(), UKItems.NAILGUN.get()})
				net.minecraft.client.item.ModelPredicateProviderRegistry.register(item, new net.minecraft.util.Identifier("ultracraft", "alt"), (stack, world, entity, seed) -> com.ultracraft.item.UKWeaponItem.alternate(stack) ? 1f : 0f);
			Net.onClient(BeamPayload.class, BeamRenderer::add);
			Net.onClient(HitPayload.class, WeaponClient.INSTANCE::onHit);
			Net.onClient(PointsSyncPayload.class, StyleState.INSTANCE::onPointsSync);
			Net.onClient(com.ultracraft.net.ParryWarnPayload.class, com.ultracraft.client.weapon.ParryWarnFx::add);
			Net.onClient(com.ultracraft.net.OwnedPayload.class, com.ultracraft.client.shop.ShopClient::onOwned);
			Net.onClient(ExplosionPayload.class, ExplosionFx::add);
			Net.onClient(com.ultracraft.net.GasolinePayload.class, com.ultracraft.client.weapon.GasolineClient::onPayload);
			Net.onClient(LaunchPayload.class, m -> {
				MinecraftClient c = MinecraftClient.getInstance();
				if (c.player == null) return;
				// NewMovement's transform sits at the capsule centre
				V1Movement.explosionLaunch(V1.INSTANCE, c.player.getPos().add(0, 0.9, 0), m.pos(), m.strength(), m.maxDistance(), m.rocketPush());
			});
		}));

		IEventBus bus = MinecraftForge.EVENT_BUS;
		bus.addListener(com.ultracraft.client.shop.InventoryPoints::onBackground);
		// ULTRAKILL has no speed FOV: the dash key is Minecraft's sprint key, so each dash briefly sprints and vanilla
		// widened the view and eased it back (a bounce after the dash). Off while V1 drives the player.
		Tune.def("cam.vanillaSpeedFov", 0, "1 = keep Minecraft's sprint / speed FOV change under V1 (it bounces after a dash)");
		bus.addListener((net.minecraftforge.client.event.ComputeFovModifierEvent e) -> {
			if (e.getPlayer() == MinecraftClient.getInstance().player && V1Movement.isActive() && !Tune.on("cam.vanillaSpeedFov")) e.setNewFovModifier(1f);
		});
		bus.addListener((TickEvent.ClientTickEvent e) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (e.phase == TickEvent.Phase.START) {
				if (!controlsChecked && client.options != null) {
					controlsChecked = true;
					ControlDefaults.applyOnce(client);
				}
				Tune.poll();
				UKKeys.tick(client);
				com.ultracraft.client.weapon.WorldUse.tick(client);
				// Minecraft keeps ticking the client under the pause menu (only the world stops): the game's clock-driven
				// state - weapon charges (the Sharpshooter's three came back while paused: user), the style meter, the
				// chainsaw / nails / rockets - waits, as ULTRAKILL's does at timeScale 0
				boolean paused = client.isPaused();
				if (!paused) WeaponClient.INSTANCE.tick(client);
				com.ultracraft.client.shop.ShopMode.tick(client);
				if (!paused) com.ultracraft.client.weapon.ChainsawFx.tick(client);
				if (!paused) com.ultracraft.client.weapon.NailGhosts.tick(client);
				if (!paused) com.ultracraft.client.weapon.RocketSounds.tick(client);
				com.ultracraft.client.v1.UnderwaterFx.tick();
				com.ultracraft.client.v1.UnderwaterAudio.tick();
				com.ultracraft.client.weapon.GasolineClient.tick(client);
				com.ultracraft.client.weapon.GrappleOrbRenderer.tick(client);
				if (!paused) StyleState.INSTANCE.tick(client);
			}
		});
		bus.addListener((net.minecraftforge.client.event.InputEvent.Key e) -> UKKeys.onKey(MinecraftClient.getInstance(), e.getKey(), e.getScanCode(), e.getAction()));
		bus.addListener((net.minecraftforge.client.event.InputEvent.MouseButton.Pre e) -> UKKeys.onMouse(MinecraftClient.getInstance(), e.getButton(), e.getAction()));
		bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
			V1.INSTANCE.resetOnSpawn();
			WeaponClient.INSTANCE.reset();
			StyleState.INSTANCE.reset();
			BeamRenderer.clear();
			ExplosionFx.clear();
			com.ultracraft.client.render.DynamicLights.clear();
			com.ultracraft.client.weapon.GasolineClient.clear();
		});
		bus.addListener((ClientPlayerNetworkEvent.Clone e) -> {
			V1.INSTANCE.resetOnSpawn();
			// NewMovement.Respawn: WeaponCharges.MaxCharges (a respawn after death, not a dimension change)
			if (e.getOldPlayer().isDead()) WeaponClient.INSTANCE.maxCharges();
		});
		// V1 has no hunger or armor bars; its own health and stamina HUD replaces the hearts.
		bus.addListener((RenderGuiOverlayEvent.Pre e) -> {
			var id = e.getOverlay().id();
			if (id.equals(VanillaGuiOverlay.PLAYER_HEALTH.id()) || id.equals(VanillaGuiOverlay.FOOD_LEVEL.id())
				|| id.equals(VanillaGuiOverlay.ARMOR_LEVEL.id()) || id.equals(VanillaGuiOverlay.AIR_LEVEL.id())) e.setCanceled(true);
		});
		// While an ULTRAKILL weapon is held, left/right click belong to it (Fire1 / Fire2), not to vanilla.
		bus.addListener((InputEvent.InteractionKeyMappingTriggered e) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			// clash mode (third person): left click is the spin with anything in hand; right click stays Minecraft's
			boolean thirdPerson = !client.options.getPerspective().isFirstPerson();
			if (thirdPerson && e.isAttack()) {
				e.setSwingHand(false);
				e.setCanceled(true);
				return;
			}
			if (thirdPerson) return;
			// at a Terminal the clicks press its screen (and right click zooms), whatever is in hand
			if (com.ultracraft.client.shop.ShopMode.active() && (e.isAttack() || e.isUseItem())) {
				e.setSwingHand(false);
				e.setCanceled(true);
				return;
			}
			if ((WeaponClient.holdingUkWeapon(client.player) || com.ultracraft.client.weapon.OffhandGun.active(client.player)) && (e.isAttack() || e.isUseItem())) {
				e.setSwingHand(false);
				e.setCanceled(true);
			}
		});
		bus.addListener((TickEvent.RenderTickEvent e) -> { if (e.phase == TickEvent.Phase.START) { HandRenderer.advanceRigs(); UKSound.pump(); } else HandRenderer.holdFrame(); });
		bus.addListener((TickEvent.ClientTickEvent e) -> { if (e.phase == TickEvent.Phase.START) HandRenderer.endFreeze(); });
		bus.addListener((TickEvent.ClientTickEvent e) -> { if (e.phase == TickEvent.Phase.END) com.ultracraft.client.weapon.WhiplashClient.INSTANCE.afterTick(MinecraftClient.getInstance()); });
		bus.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase != TickEvent.Phase.END) return;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.isPaused()) return;
			com.ultracraft.client.weapon.ZapperClient.INSTANCE.tick(mc);
			// (the off-hand zapper's shock goes to the server as the off-hand weapon's)
			com.ultracraft.client.weapon.OffhandGun.run(mc.player, () -> com.ultracraft.client.weapon.ZapperClient.OFF.tick(mc));
		});
		bus.addListener((TickEvent.ClientTickEvent e) -> { if (e.phase == TickEvent.Phase.END) com.ultracraft.client.render.DynamicLights.tick(MinecraftClient.getInstance()); });
		bus.addListener(HandRenderer::onRenderHand);
		bus.addListener(com.ultracraft.client.shop.TerminalHighlight::onHighlight);
		bus.addListener(com.ultracraft.client.v1.V1Renderer::onRenderPlayer);
		bus.addListener(BeamRenderer::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.NailGhosts::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.MagnetFx::onRenderLevel);
		// (the gasoline fire before the blasts: a blast drawn first hid what lay behind it)
		bus.addListener(com.ultracraft.client.weapon.GasolineClient::onRenderLevel);
		bus.addListener(ExplosionFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.BloodFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.ShockwaveFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.HammerFx::onRenderLevel);
		bus.addListener(ProjectileRenderer::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.ShellFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.SteamFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.SparkFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.v1.SpeedFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.CoinRenderer::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.ChainsawFx::onRenderLevel);
		bus.addListener(com.ultracraft.client.weapon.ParryWarnFx::onRenderLevel);
		bus.addListener((RenderLevelStageEvent e) -> com.ultracraft.client.weapon.WhiplashClient.INSTANCE.onRenderLevel(e));
		bus.addListener((RenderLevelStageEvent e) -> { com.ultracraft.client.weapon.ZapperClient.INSTANCE.onRenderLevel(e); com.ultracraft.client.weapon.ZapperClient.OFF.onRenderLevel(e); });
	}
}
