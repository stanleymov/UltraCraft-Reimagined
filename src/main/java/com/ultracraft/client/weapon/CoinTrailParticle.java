package com.ultracraft.client.weapon;

import com.ultracraft.entity.CoinEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * The coin's trail drawn as a particle: shader packs (Complementary) draw particles after their deferred clouds,
 * while entity-pass geometry is covered by them, and depth-writing it instead blanks the sky behind it. One per
 * coin, alive as long as the coin flies; the white comes from the centre of the vanilla "flash" particle sprite.
 */
public final class CoinTrailParticle extends Particle {
	private final CoinEntity coin;
	private static Sprite white;

	public CoinTrailParticle(ClientWorld world, CoinEntity coin) {
		super(world, coin.getX(), coin.getY(), coin.getZ());
		this.coin = coin;
		this.maxAge = Integer.MAX_VALUE;
		this.gravityStrength = 0;
		this.collidesWithWorld = false;
	}

	@Override
	public void tick() {
		if (coin.isRemoved() || coin.isLanded()) {
			markDead();
			return;
		}
		prevPosX = x;
		prevPosY = y;
		prevPosZ = z;
		setPos(coin.getX(), coin.getY(), coin.getZ());
		setBoundingBox(new Box(coin.getX() - 8, coin.getY() - 8, coin.getZ() - 8, coin.getX() + 8, coin.getY() + 8, coin.getZ() + 8));
	}

	@Override
	public boolean shouldCull() {
		return false;
	}

	@Override
	public void buildGeometry(VertexConsumer vc, Camera camera, float tickDelta) {
		if (white == null) {
			// the particle atlas lives in the texture manager (the model manager does not hold it)
			SpriteAtlasTexture atlas = (SpriteAtlasTexture) MinecraftClient.getInstance().getTextureManager().getTexture(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
			white = atlas.getSprite(new Identifier("minecraft", "flash"));
		}
		float u = (white.getMinU() + white.getMaxU()) / 2, v = (white.getMinV() + white.getMaxV()) / 2;
		Vec3d cam = camera.getPos();
		CoinRenderer.trailGeometry(coin, tickDelta, cam, (wx, wy, wz, a) ->
			vc.vertex((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z)).texture(u, v).color(1f, 1f, 1f, a).light(0xF000F0).next());
	}

	@Override
	public ParticleTextureSheet getType() {
		return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
	}
}
