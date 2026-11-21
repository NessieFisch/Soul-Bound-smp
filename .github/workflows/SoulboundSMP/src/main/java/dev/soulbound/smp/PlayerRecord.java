package dev.soulbound.smp;

import org.bukkit.Location;

import java.util.UUID;

public final class PlayerRecord {
    public final UUID uuid;
    public String name;
    /** Dead and waiting (or permanently gone). */
    public boolean eliminated;
    /** The single season revive has been spent (or lost). */
    public boolean reviveUsed;
    /** Permanently out: banned, can never come back. */
    public boolean permanent;
    /** Ritual succeeded while they were offline; applied on next join. */
    public boolean pendingRevive;
    public Location deathLocation;
    public Location pendingLocation;

    public PlayerRecord(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean canBeRevived() {
        return eliminated && !reviveUsed && !permanent && !pendingRevive;
    }
}
