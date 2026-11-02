package com.ultracraft.client;

import com.ultracraft.UkConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/** The config screen behind the Mods list's settings button: on/off switches for the UltraCraft options. */
public final class UkConfigScreen extends Screen {
	private final Screen parent;

	public UkConfigScreen(Screen parent) {
		super(Text.literal("UltraCraft Reimagined options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// the volume across the top, the switches in two columns under it (one column ran into Done on small windows)
		int x = width / 2 - 155, x2 = width / 2 + 5, y = height / 6;
		boolean loaded = UkConfig.SPEC.isLoaded();
		double vol = loaded ? UkConfig.SOUND_VOLUME.get() : 1.0;
		addDrawableChild(new net.minecraft.client.gui.widget.SliderWidget(x, y, 310, 20, volumeLabel(vol), vol / 1.25) {
			@Override
			protected void updateMessage() {
				setMessage(volumeLabel(volume(value)));
			}

			@Override
			protected void applyValue() {
				if (loaded) UkConfig.SOUND_VOLUME.set(volume(value));
			}
		}).setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("ULTRAKILL's sounds in UltraCraft (125% = ULTRAKILL's own level)")));
		addDrawableChild(new net.minecraft.client.gui.widget.SliderWidget(x, y + 24, 310, 20, zoomLabel(UkConfig.zoomSensitivity()), (UkConfig.zoomSensitivity() - 0.05) / 0.95) {
			@Override
			protected void updateMessage() {
				setMessage(zoomLabel(sens(value)));
			}

			@Override
			protected void applyValue() {
				if (loaded) UkConfig.ZOOM_SENSITIVITY.set(sens(value));
			}
		});
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.speedometer())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("The SPEED: u/s panel under the health bar")))
			.build(x, y + 48, 150, 20, Text.literal("Speedometer"), (b, v) -> { if (loaded) UkConfig.SPEEDOMETER.set(v); }));
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.parryFlash())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("White screen flash on a successful parry")))
			.build(x2, y + 48, 150, 20, Text.literal("Parry flash"), (b, v) -> { if (loaded) UkConfig.PARRY_FLASH.set(v); }));
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.airBrake())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Let go of the movement keys in mid-air to stop drifting sideways and land on a single block")))
			.build(x, y + 72, 150, 20, Text.literal("Air brake"), (b, v) -> { if (loaded) UkConfig.AIR_BRAKE.set(v); }));
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.shortHop())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Tap jump for a short hop of about a block; hold it for the full jump")))
			.build(x2, y + 72, 150, 20, Text.literal("Short hop"), (b, v) -> { if (loaded) UkConfig.SHORT_HOP.set(v); }));
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.drowning())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Minecraft drowning under water (off: V1 never runs out of air, as in ULTRAKILL)")))
			.build(x, y + 96, 150, 20, Text.literal("Drowning"), (b, v) -> { if (loaded) UkConfig.DROWNING.set(v); }));
		addDrawableChild(CyclingButtonWidget.onOffBuilder(UkConfig.lavaBounce())
			.tooltip(v -> net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("ULTRAKILL's lava: it throws you back out and costs 2 hearts, without setting you on fire (off: Minecraft's lava)")))
			.build(x2, y + 96, 150, 20, Text.literal("ULTRAKILL lava"), (b, v) -> { if (loaded) UkConfig.LAVA_BOUNCE.set(v); }));
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(width / 2 - 100, height - 40, 200, 20).build());
	}

	/** Slider 0..1 to 0..125 % in steps of 5. */
	private static double volume(double slider) {
		return Math.round(slider * 1.25 * 20) / 20.0;
	}

	private static Text volumeLabel(double v) {
		return Text.literal("Sound volume: " + Math.round(v * 100) + "%");
	}

	/** Slider 0..1 to 5..100 % in steps of 5. */
	private static double sens(double slider) {
		return Math.round((0.05 + slider * 0.95) * 20) / 20.0;
	}

	private static Text zoomLabel(double v) {
		return Text.literal("Zoomed aim sensitivity: " + Math.round(v * 100) + "%");
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (UkConfig.SPEC.isLoaded()) UkConfig.SPEC.save();
		if (client != null) client.setScreen(parent);
	}
}
