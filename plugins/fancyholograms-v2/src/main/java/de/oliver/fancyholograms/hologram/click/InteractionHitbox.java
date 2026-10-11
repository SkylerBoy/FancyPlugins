package de.oliver.fancyholograms.hologram.click;

import de.oliver.fancysitula.api.entities.FS_Entity;
import de.oliver.fancysitula.api.packets.FS_ClientboundSetEntityDataPacket;
import de.oliver.fancysitula.api.utils.entityData.FS_InteractionData;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * A packet-only interaction entity used as clickable hitbox of a hologram (or a group of lines).
 * Interaction entities are invisible, axis-aligned boxes whose position is the bottom center of the box.
 */
public final class InteractionHitbox extends FS_Entity {

    private final FS_ClientboundSetEntityDataPacket.EntityData widthData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.WIDTH, null);
    private final FS_ClientboundSetEntityDataPacket.EntityData heightData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.HEIGHT, null);
    // "response" makes the client swing the arm on right click. It's disabled because arm swings are used to
    // detect left clicks (see HologramClickListener).
    private final FS_ClientboundSetEntityDataPacket.EntityData responseData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.RESPONSE, false);

    public InteractionHitbox() {
        super(EntityType.INTERACTION);
    }

    public void setSize(float width, float height) {
        this.widthData.setValue(width);
        this.heightData.setValue(height);
    }

    public float getWidth() {
        return widthData.getValue() instanceof Float width ? width : 0;
    }

    public float getHeight() {
        return heightData.getValue() instanceof Float height ? height : 0;
    }

    /**
     * @return the distance from the origin to the hitbox along the direction, or -1 if the ray doesn't hit it
     */
    public double rayDistance(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        final double halfWidth = getWidth() / 2.0;
        final double[] min = {getX() - halfWidth, getY(), getZ() - halfWidth};
        final double[] max = {getX() + halfWidth, getY() + getHeight(), getZ() + halfWidth};
        final double[] origin = {ox, oy, oz};
        final double[] direction = {dx, dy, dz};

        double tMin = 0;
        double tMax = maxDistance;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0E-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1;
                }
                continue;
            }

            double t1 = (min[axis] - origin[axis]) / direction[axis];
            double t2 = (max[axis] - origin[axis]) / direction[axis];
            if (t1 > t2) {
                final double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1;
            }
        }
        return tMin;
    }

    @Override
    public List<FS_ClientboundSetEntityDataPacket.EntityData> getEntityData() {
        List<FS_ClientboundSetEntityDataPacket.EntityData> entityData = super.getEntityData();
        entityData.add(widthData);
        entityData.add(heightData);
        entityData.add(responseData);
        return entityData;
    }
}
