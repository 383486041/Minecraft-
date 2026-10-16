package dev.codex.flightarmor.client.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 跟随玩家的可持续声音实例。
 *
 * <p>业务层只更新目标音量和音高；实例自身负责逐 tick 淡入、淡出、位置跟随和结束清理，
 * 从而不需要再按固定间隔重复播放短音效伪装循环。</p>
 */
public final class ArmorLoopSound extends AbstractTickableSoundInstance {
	private static final float SILENCE = 0.001F;
	private final LivingEntity entity;
	private final boolean listenerRelative;
	private float targetVolume;
	private float targetPitch = 1.0F;

	public ArmorLoopSound(LivingEntity entity, SoundEvent event, boolean listenerRelative) {
		super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
		this.entity = entity;
		this.listenerRelative = listenerRelative;
		this.looping = true;
		this.delay = 0;
		this.relative = listenerRelative;
		this.attenuation = listenerRelative ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
		this.volume = SILENCE;
		this.pitch = 1.0F;
		updatePosition();
	}

	public void setTarget(float volume, float pitch) {
		this.targetVolume = Mth.clamp(volume, 0.0F, 1.0F);
		this.targetPitch = Mth.clamp(pitch, 0.5F, 2.0F);
	}

	public void fadeOut() {
		targetVolume = 0.0F;
	}

	public void stopImmediately() {
		stop();
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (entity.isRemoved() || !entity.isAlive()) {
			stop();
			return;
		}

		updatePosition();
		// 缓慢交叉淡化让转子、稳定执行器和气流像同一套连续工作的动力总成。
		float volumeResponse = targetVolume > volume ? 0.16F : 0.12F;
		volume += (targetVolume - volume) * volumeResponse;
		pitch += (targetPitch - pitch) * 0.08F;
		if (targetVolume <= SILENCE && volume <= SILENCE * 2.0F) {
			stop();
		}
	}

	private void updatePosition() {
		if (listenerRelative) {
			x = 0.0D;
			y = 0.0D;
			z = 0.0D;
			return;
		}
		x = entity.getX();
		y = entity.getY() + entity.getBbHeight() * 0.62D;
		z = entity.getZ();
	}
}
