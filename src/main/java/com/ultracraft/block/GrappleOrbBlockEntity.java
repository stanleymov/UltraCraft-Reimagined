package com.ultracraft.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class GrappleOrbBlockEntity extends BlockEntity {
	/** Client: nanoTime the Whiplash last grabbed this orb (HookPoint.Hooked: its grabParticle, HookPointGrab). */
	public long grabbedNanos;
	/** Client, HookPoint's look: outerOrb / innerOrb scale (prefab 5 and 3.5), the Spin angles (°) and the last frame's time. */
	public float outerScale = 5, innerScale = 3.5f, coreSpin, arch1Spin, arch2Spin, innerSpin;
	public long lastFrameNanos;

	/** Client: the loaded orbs (their hum keeps going while V1 is near, also with the orb off screen). */
	public static final java.util.Set<GrappleOrbBlockEntity> CLIENT = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	public GrappleOrbBlockEntity(BlockPos pos, BlockState state) {
		super(UKBlocks.GRAPPLE_ORB_ENTITY.get(), pos, state);
	}

	@Override
	public void setWorld(World world) {
		super.setWorld(world);
		if (world.isClient) CLIENT.add(this);
	}

	@Override
	public void markRemoved() {
		super.markRemoved();
		CLIENT.remove(this);
	}
}
