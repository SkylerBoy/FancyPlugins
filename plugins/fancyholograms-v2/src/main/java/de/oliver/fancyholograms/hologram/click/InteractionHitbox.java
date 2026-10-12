package de.oliver.fancyholograms.hologram.click;

import de.oliver.fancysitula.api.entities.FS_Entity;
import de.oliver.fancysitula.api.packets.FS_ClientboundSetEntityDataPacket;
import de.oliver.fancysitula.api.utils.entityData.FS_InteractionData;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * A packet-only interaction entity used as clickable hitbox of a hologram.
 * Interaction entities are invisible, axis-aligned boxes whose position is the bottom center of the box.
 */
public final class InteractionHitbox extends FS_Entity {

    private final FS_ClientboundSetEntityDataPacket.EntityData widthData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.WIDTH, null);
    private final FS_ClientboundSetEntityDataPacket.EntityData heightData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.HEIGHT, null);
    // "response" makes the client swing the arm when interacting with the hitbox. Not needed for holograms.
    private final FS_ClientboundSetEntityDataPacket.EntityData responseData = new FS_ClientboundSetEntityDataPacket.EntityData(FS_InteractionData.RESPONSE, false);

    public InteractionHitbox() {
        super(EntityType.INTERACTION);
    }

    public void setSize(float width, float height) {
        this.widthData.setValue(width);
        this.heightData.setValue(height);
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
