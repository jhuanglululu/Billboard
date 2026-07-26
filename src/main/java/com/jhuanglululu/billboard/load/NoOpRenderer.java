package com.jhuanglululu.billboard.load;

import com.jhuanglululu.billboard.runtime.ParticleSpec;
import com.jhuanglululu.billboard.runtime.Renderer;

/**
 * A renderer that draws nothing, used to instantiate an animation far enough to check its exports
 * and ABI version at load time ({@link ModuleCheck}). Load-time validation constructs the real
 * {@link com.jhuanglululu.billboard.runtime.AnimationInstance} so the handshake it performs is
 * exactly the production one — but no animation code runs, so none of these methods is ever called.
 */
final class NoOpRenderer implements Renderer {

    @Override
    public void spawnBlockDisplay(int id, String blockState, double x, double y, double z) {}

    @Override
    public void spawnItemDisplay(int id, String item, double x, double y, double z) {}

    @Override
    public void spawnTextDisplay(int id, String text, double x, double y, double z) {}

    @Override
    public void spawnArmorStand(int id, double x, double y, double z) {}

    @Override
    public void spawnItem(int id, String item, double x, double y, double z) {}

    @Override
    public void setPosition(int id, double x, double y, double z, long overTicks) {}

    @Override
    public void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks) {}

    @Override
    public void setScale(int id, double sx, double sy, double sz, long overTicks) {}

    @Override
    public void setBlock(int id, String blockState) {}

    @Override
    public void setItem(int id, String item) {}

    @Override
    public void setDisplayContext(int id, int context) {}

    @Override
    public void setBillboardMode(int id, int mode) {}

    @Override
    public void setText(int id, String text) {}

    @Override
    public void setTextBackground(int id, long argb) {}

    @Override
    public void setTextOpacity(int id, long opacity) {}

    @Override
    public void setLineWidth(int id, long width) {}

    @Override
    public void setTextFlags(int id, int flags) {}

    @Override
    public void setPose(int id, int part, double xDeg, double yDeg, double zDeg, long overTicks) {}

    @Override
    public void setEquipment(int id, int slot, String item) {}

    @Override
    public void setStandFlags(int id, int flags) {}

    @Override
    public void setYaw(int id, double yawDegrees, long overTicks) {}

    @Override
    public void despawn(int id) {}

    @Override
    public void playSound(String name, double x, double y, double z, int category, double volume,
            double pitch) {}

    @Override
    public void emitParticle(ParticleSpec.Emission emission) {}

    @Override
    public void tickTweens() {}
}
