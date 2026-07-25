package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Identity, permissions and membership for ADMIN territories: the server-building layer on top of Easy
 * Factions' admin claims.
 *
 * Easy Factions gives admin claims no identity of their own — it stores the claiming admin's UUID as the
 * owner, paints them all one config colour, and applies ONE global restriction list to every admin claim on
 * the server. So spawn, a PvP arena and a build plot can never behave differently. Everything that makes
 * them distinguishable lives here:
 *
 * <ul>
 *   <li><b>Name + colour</b> so the map reads "Spawn" / "Arena", not a wall of identical purple.
 *       (Also a necessity: {@code ClaimManager.load()} re-derives claim colours by type on world load and
 *       force-sets every ADMIN claim back to {@code ServerConfig.adminClaimColor}, so a colour written into
 *       the Easy Factions claim silently reverts on the next restart. Ours is authoritative.)</li>
 *   <li><b>Permission switches</b> ({@link AdminPerm}) per territory, overriding the global list.</li>
 *   <li><b>Members</b> — players the operator has trusted with the territory, e.g. moderators over spawn or
 *       a player given their own plot. Members are exempt from that territory's restrictions.</li>
 *   <li><b>Parent / child</b> — a child is a sub-region carved out of a parent, one level deep.</li>
 * </ul>
 *
 * <h2>How children work (and why they are invisible outside this mod)</h2>
 * A child does NOT take its chunks over from the parent. The chunk stays exactly one Easy Factions ADMIN
 * claim owned by the parent; the child is purely our own overlay recorded here. Two consequences, both
 * deliberate:
 * <ul>
 *   <li>A child can only ever exist on chunks the parent already holds, so "a child must be inside its
 *       parent" is enforced by construction rather than by validation that could drift.</li>
 *   <li>Anything reading Easy Factions' claim data — the world map, Here Be Doodles, War 'n Nobility's War
 *       Frame, any atlas — sees the parent and only the parent. Children are shown in the Territory Table
 *       and nowhere else, so a server's plot subdivisions never clutter the players' map.</li>
 * </ul>
 * Permissions resolve deepest-first: inside a child, the child's switches decide; elsewhere in the parent,
 * the parent's do. Membership resolves the other way — a member of the parent is trusted everywhere in it,
 * children included, because a moderator over spawn should not be locked out of a plot inside spawn.
 *
 * Mirrors {@link TerritoryNames} (personal claims) and {@link PurchasedClaims} (faction claim slots).
 */
public class AdminTerritories extends SavedData {

    private static final String FILE = "territory_admin_zones";

    /** Longest admin territory name we keep, matching {@link TerritoryNames}' limit. */
    public static final int MAX_NAME = 48;

    /**
     * One admin territory. {@code color} is RGB masked to 24 bits, {@code perms} is an {@link AdminPerm}
     * mask ({@link AdminPerm#NOT_SET} = defer to Easy Factions' global admin config), {@code parent} is the
     * empty string for a top-level territory or the parent's name for a child, and {@code members} are the
     * players exempt from this territory's restrictions.
     */
    public record Territory(String name, int color, int perms, String parent, Set<UUID> members) {

        public boolean isChild() {
            return !parent.isEmpty();
        }

        public Territory withColor(int rgb) {
            return new Territory(name, rgb & 0xFFFFFF, perms, parent, members);
        }

        public Territory withPerms(int mask) {
            return new Territory(name, color, mask, parent, members);
        }

        public Territory withMembers(Set<UUID> next) {
            return new Territory(name, color, perms, parent, Set.copyOf(next));
        }
    }

    /** dimension id -> territory name -> territory. */
    private final Map<String, Map<String, Territory>> territories = new HashMap<>();
    /** dimension id -> packed chunk long -> name of the PARENT territory holding that chunk. */
    private final Map<String, Map<Long, String>> parentAt = new HashMap<>();
    /** dimension id -> packed chunk long -> name of the CHILD territory covering that chunk, if any. */
    private final Map<String, Map<Long, String>> childAt = new HashMap<>();

    public static AdminTerritories get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AdminTerritories::new, AdminTerritories::load), FILE);
    }

    private static String key(ResourceKey<Level> dim) {
        return dim.location().toString();
    }

    public static String clean(String name) {
        String s = name == null ? "" : name.strip();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    // ---- lookups ------------------------------------------------------------------------------------

    /** The territory called {@code name} in {@code dim}, or {@code null}. */
    public Territory get(ResourceKey<Level> dim, String name) {
        Map<String, Territory> dimTerritories = territories.get(key(dim));
        return dimTerritories != null ? dimTerritories.get(clean(name)) : null;
    }

    /** Every territory in {@code dim}: parents first, each immediately followed by its children. */
    public List<Territory> listOrdered(ResourceKey<Level> dim) {
        Map<String, Territory> dimTerritories = territories.get(key(dim));
        if (dimTerritories == null) return List.of();
        List<Territory> out = new ArrayList<>();
        for (Territory t : dimTerritories.values()) {
            if (t.isChild()) continue;
            out.add(t);
            for (Territory c : dimTerritories.values()) {
                if (c.isChild() && c.parent().equals(t.name())) out.add(c);
            }
        }
        // a child whose parent was deleted would otherwise vanish from the GUI and be un-editable
        for (Territory t : dimTerritories.values()) {
            if (t.isChild() && !dimTerritories.containsKey(t.parent())) out.add(t);
        }
        return out;
    }

    /** Name of the parent territory holding {@code chunk}, or "" if the chunk is not an admin claim of ours. */
    public String parentNameAt(ResourceKey<Level> dim, long chunk) {
        Map<Long, String> map = parentAt.get(key(dim));
        String name = map != null ? map.get(chunk) : null;
        return name != null ? name : "";
    }

    /** Name of the child territory covering {@code chunk}, or "" if the chunk is not inside one. */
    public String childNameAt(ResourceKey<Level> dim, long chunk) {
        Map<Long, String> map = childAt.get(key(dim));
        String name = map != null ? map.get(chunk) : null;
        return name != null ? name : "";
    }

    /**
     * The territory whose PERMISSIONS govern {@code chunk}: the child if the chunk is inside one, otherwise
     * the parent, or {@code null} when the chunk has no record (in which case Easy Factions' global admin
     * config still applies and this mod stays out of the way).
     */
    public Territory governing(ResourceKey<Level> dim, long chunk) {
        String child = childNameAt(dim, chunk);
        if (!child.isEmpty()) {
            Territory t = get(dim, child);
            if (t != null) return t;
        }
        String parent = parentNameAt(dim, chunk);
        return parent.isEmpty() ? null : get(dim, parent);
    }

    /**
     * Whether {@code player} is trusted on {@code chunk}. True when they are a member of the child covering
     * it OR of the parent holding it — trust flows downhill, so a moderator over spawn is also trusted in
     * every plot inside spawn, while a plot's own member is trusted only there.
     */
    public boolean isTrusted(ResourceKey<Level> dim, long chunk, UUID player) {
        if (player == null) return false;
        String child = childNameAt(dim, chunk);
        if (!child.isEmpty()) {
            Territory t = get(dim, child);
            if (t != null && t.members().contains(player)) return true;
        }
        String parent = parentNameAt(dim, chunk);
        if (!parent.isEmpty()) {
            Territory t = get(dim, parent);
            return t != null && t.members().contains(player);
        }
        return false;
    }

    /** Chunks belonging to the parent territory {@code name} in {@code dim}. */
    public Set<Long> chunksOfParent(ResourceKey<Level> dim, String name) {
        Map<Long, String> map = parentAt.get(key(dim));
        if (map == null) return Set.of();
        String want = clean(name);
        Set<Long> out = new HashSet<>();
        map.forEach((chunk, owner) -> {
            if (owner.equals(want)) out.add(chunk);
        });
        return out;
    }

    /** Chunks covered by the child territory {@code name} in {@code dim}. */
    public Set<Long> chunksOfChild(ResourceKey<Level> dim, String name) {
        Map<Long, String> map = childAt.get(key(dim));
        if (map == null) return Set.of();
        String want = clean(name);
        Set<Long> out = new HashSet<>();
        map.forEach((chunk, owner) -> {
            if (owner.equals(want)) out.add(chunk);
        });
        return out;
    }

    // ---- mutations ----------------------------------------------------------------------------------

    /** Create the territory if it is new, or return the existing one unchanged. */
    public Territory ensure(ResourceKey<Level> dim, String name, int color, int perms, String parent) {
        String clean = clean(name);
        Map<String, Territory> dimTerritories = territories.computeIfAbsent(key(dim), k -> new HashMap<>());
        Territory existing = dimTerritories.get(clean);
        if (existing != null) return existing;
        Territory made = new Territory(clean, color & 0xFFFFFF, perms, clean(parent), Set.of());
        dimTerritories.put(clean, made);
        setDirty();
        return made;
    }

    private void put(ResourceKey<Level> dim, Territory t) {
        territories.computeIfAbsent(key(dim), k -> new HashMap<>()).put(t.name(), t);
        setDirty();
    }

    /** Record {@code chunk} as part of the PARENT territory {@code name}, creating the territory if needed. */
    public void assignParent(ResourceKey<Level> dim, long chunk, String name, int color, int perms) {
        String clean = clean(name);
        ensure(dim, clean, color, perms, "");
        parentAt.computeIfAbsent(key(dim), k -> new HashMap<>()).put(chunk, clean);
        setDirty();
    }

    /**
     * Put {@code chunk} inside the CHILD territory {@code name}. Refuses when the chunk is not held by
     * {@code parent}, which is the single rule that keeps a child inside its parent.
     *
     * @return true if the chunk was assigned
     */
    public boolean assignChild(ResourceKey<Level> dim, long chunk, String name, String parent, int color, int perms) {
        String cleanChild = clean(name);
        String cleanParent = clean(parent);
        if (cleanChild.isEmpty() || cleanParent.isEmpty()) return false;
        if (!parentNameAt(dim, chunk).equals(cleanParent)) return false;
        ensure(dim, cleanChild, color, perms, cleanParent);
        childAt.computeIfAbsent(key(dim), k -> new HashMap<>()).put(chunk, cleanChild);
        setDirty();
        return true;
    }

    /** Take {@code chunk} back out of whatever child covers it. The parent claim is untouched. */
    public void clearChild(ResourceKey<Level> dim, long chunk) {
        Map<Long, String> map = childAt.get(key(dim));
        if (map == null) return;
        String removed = map.remove(chunk);
        if (removed == null) return;
        if (map.isEmpty()) childAt.remove(key(dim));
        // a child with no chunks left is a ghost in every list: drop it with its last chunk
        if (chunksOfChild(dim, removed).isEmpty()) removeTerritory(dim, removed);
        setDirty();
    }

    /**
     * Forget a chunk's admin identity entirely — call whenever an admin chunk is UNCLAIMED, or its name and
     * permissions leak to whoever claims that ground next. Any child covering it loses it too.
     */
    public void clearChunk(ResourceKey<Level> dim, long chunk) {
        clearChild(dim, chunk);
        Map<Long, String> map = parentAt.get(key(dim));
        if (map == null) return;
        String removed = map.remove(chunk);
        if (removed == null) return;
        if (map.isEmpty()) parentAt.remove(key(dim));
        if (chunksOfParent(dim, removed).isEmpty()) {
            removeTerritory(dim, removed);
            // orphaned children of a deleted parent hold nothing real any more
            for (Territory t : listOrdered(dim)) {
                if (t.isChild() && t.parent().equals(removed)) removeTerritory(dim, t.name());
            }
        }
        setDirty();
    }

    private void removeTerritory(ResourceKey<Level> dim, String name) {
        Map<String, Territory> dimTerritories = territories.get(key(dim));
        if (dimTerritories == null) return;
        if (dimTerritories.remove(clean(name)) != null) {
            if (dimTerritories.isEmpty()) territories.remove(key(dim));
            setDirty();
        }
    }

    /** Set a territory's permission mask. Returns false if there is no such territory. */
    public boolean setPerms(ResourceKey<Level> dim, String name, int perms) {
        Territory t = get(dim, name);
        if (t == null) return false;
        put(dim, t.withPerms(perms));
        return true;
    }

    /** Set a territory's border colour (drawn in the Territory Table; the world map keeps the parent's). */
    public boolean setColor(ResourceKey<Level> dim, String name, int rgb) {
        Territory t = get(dim, name);
        if (t == null) return false;
        put(dim, t.withColor(rgb));
        return true;
    }

    /** Trust {@code player} in this territory. Returns false if there is no such territory. */
    public boolean addMember(ResourceKey<Level> dim, String name, UUID player) {
        Territory t = get(dim, name);
        if (t == null || player == null) return false;
        Set<UUID> next = new LinkedHashSet<>(t.members());
        if (!next.add(player)) return true;
        put(dim, t.withMembers(next));
        return true;
    }

    public boolean removeMember(ResourceKey<Level> dim, String name, UUID player) {
        Territory t = get(dim, name);
        if (t == null || player == null) return false;
        Set<UUID> next = new LinkedHashSet<>(t.members());
        if (!next.remove(player)) return true;
        put(dim, t.withMembers(next));
        return true;
    }

    // ---- persistence --------------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag dims = new CompoundTag();
        for (Map.Entry<String, Map<String, Territory>> dimEntry : territories.entrySet()) {
            CompoundTag dimTag = new CompoundTag();

            CompoundTag defs = new CompoundTag();
            for (Territory t : dimEntry.getValue().values()) {
                CompoundTag def = new CompoundTag();
                def.putInt("color", t.color());
                def.putInt("perms", t.perms());
                def.putString("parent", t.parent());
                ListTag members = new ListTag();
                for (UUID id : t.members()) members.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
                def.put("members", members);
                defs.put(t.name(), def);
            }
            dimTag.put("territories", defs);

            dimTag.put("parentAt", saveChunkMap(parentAt.get(dimEntry.getKey())));
            dimTag.put("childAt", saveChunkMap(childAt.get(dimEntry.getKey())));
            dims.put(dimEntry.getKey(), dimTag);
        }
        tag.put("dims", dims);
        tag.putInt("version", 2);
        return tag;
    }

    private static CompoundTag saveChunkMap(Map<Long, String> map) {
        CompoundTag out = new CompoundTag();
        if (map != null) map.forEach((chunk, name) -> out.putString(Long.toString(chunk), name));
        return out;
    }

    public static AdminTerritories load(CompoundTag tag, HolderLookup.Provider registries) {
        AdminTerritories data = new AdminTerritories();
        if (tag.contains("dims")) {
            CompoundTag dims = tag.getCompound("dims");
            for (String dim : dims.getAllKeys()) {
                CompoundTag dimTag = dims.getCompound(dim);
                Map<String, Territory> defs = new HashMap<>();
                CompoundTag defsTag = dimTag.getCompound("territories");
                for (String name : defsTag.getAllKeys()) {
                    CompoundTag def = defsTag.getCompound(name);
                    Set<UUID> members = new LinkedHashSet<>();
                    ListTag list = def.getList("members", Tag.TAG_STRING);
                    for (int i = 0; i < list.size(); i++) {
                        try {
                            members.add(UUID.fromString(list.getString(i)));
                        } catch (IllegalArgumentException ignored) {
                            // corrupt entry: drop that member rather than fail the world load
                        }
                    }
                    defs.put(name, new Territory(name, def.getInt("color"), def.getInt("perms"),
                            def.getString("parent"), Collections.unmodifiableSet(members)));
                }
                if (!defs.isEmpty()) data.territories.put(dim, defs);
                loadChunkMap(dimTag.getCompound("parentAt"), data.parentAt, dim);
                loadChunkMap(dimTag.getCompound("childAt"), data.childAt, dim);
            }
            return data;
        }

        // ---- version 1: dim -> chunk -> {name, color}, with no territories, children or members ----
        // Rebuilt into the new shape so existing admin regions keep their names and colours across the
        // update. Their permissions read as NOT_SET, i.e. exactly the Easy Factions behaviour they had.
        CompoundTag old = tag.getCompound("zones");
        for (String dim : old.getAllKeys()) {
            CompoundTag chunks = old.getCompound(dim);
            for (String chunk : chunks.getAllKeys()) {
                try {
                    long packed = Long.parseLong(chunk);
                    CompoundTag z = chunks.getCompound(chunk);
                    String name = clean(z.getString("name"));
                    int color = z.getInt("color");
                    data.territories.computeIfAbsent(dim, k -> new HashMap<>())
                            .computeIfAbsent(name, n -> new Territory(n, color, AdminPerm.NOT_SET, "", Set.of()));
                    data.parentAt.computeIfAbsent(dim, k -> new HashMap<>()).put(packed, name);
                } catch (NumberFormatException ignored) {
                    // a hand-edited or corrupt key: drop that chunk rather than fail the whole world load
                }
            }
        }
        return data;
    }

    private static void loadChunkMap(CompoundTag src, Map<String, Map<Long, String>> dest, String dim) {
        Map<Long, String> map = new HashMap<>();
        for (String chunk : src.getAllKeys()) {
            try {
                map.put(Long.parseLong(chunk), src.getString(chunk));
            } catch (NumberFormatException ignored) {
                // corrupt key: drop that chunk rather than fail the world load
            }
        }
        if (!map.isEmpty()) dest.put(dim, map);
    }
}
