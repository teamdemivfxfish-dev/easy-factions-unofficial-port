package top.leonx.territory.world;

import java.util.List;

/**
 * The per-territory permission switches an operator can flip on an ADMIN claim.
 *
 * Easy Factions only has ONE global list of admin restrictions ({@code adminClaimRestrictions}) that applies
 * to every admin claim on the server, so a spawn area and an event arena can never have different rules.
 * These switches are stored per admin territory in {@link AdminTerritories} and override that global list.
 *
 * Each switch is one BIT in an int mask, and the bit meaning is ALLOWED (set = players may do it). A mask of
 * {@link #NOT_SET} means the territory has never been customised, in which case Easy Factions' global config
 * still decides and this mod stays out of the way entirely.
 *
 * Several raw {@link Interaction}s are grouped behind one switch on purpose: an operator wants "can players
 * open things here", not eleven near-identical toggles. Everything Easy Factions can enforce is still covered
 * between the six, so nothing silently falls through.
 */
public enum AdminPerm {

    BREAK(0, "break", Interaction.BREAK_BLOCK),
    PLACE(1, "place", Interaction.PLACE_BLOCK),
    USE(2, "use", Interaction.RIGHT_CLICK_BLOCK, Interaction.LEFT_CLICK_BLOCK,
            Interaction.RIGHT_CLICK_ITEM, Interaction.USE_BUCKET),
    ENTITIES(3, "entities", Interaction.INTERACT_ENTITY),
    ATTACK(4, "attack", Interaction.PLAYER_ATTACK),
    DAMAGE(5, "damage", Interaction.EXPLOSION_DAMAGE, Interaction.MOB_GRIEFING_DAMAGE, Interaction.PISTON_MOVE);

    /** Mask value meaning "never customised - let Easy Factions' global admin config decide". */
    public static final int NOT_SET = -1;

    private final int bit;
    private final String key;
    private final List<Interaction> covers;

    AdminPerm(int bit, String key, Interaction... covers) {
        this.bit = bit;
        this.key = key;
        this.covers = List.of(covers);
    }

    public int mask() {
        return 1 << bit;
    }

    /** Translation key for the switch label, e.g. {@code gui.territory.perm.break}. */
    public String langKey() {
        return "gui.territory.perm." + key;
    }

    /** Tooltip / sub-label key explaining what the switch covers. */
    public String descKey() {
        return "gui.territory.perm." + key + ".desc";
    }

    public List<Interaction> covers() {
        return covers;
    }

    /** The switch governing {@code interaction}, or {@code null} if nothing covers it. */
    public static AdminPerm forInteraction(Interaction interaction) {
        for (AdminPerm p : values()) {
            if (p.covers.contains(interaction)) return p;
        }
        return null;
    }

    /** Whether {@code mask} allows this switch. Meaningless for {@link #NOT_SET}; check that first. */
    public boolean allowedIn(int mask) {
        return (mask & mask()) != 0;
    }

    public static int with(int mask, AdminPerm perm, boolean allowed) {
        int base = mask == NOT_SET ? 0 : mask;
        return allowed ? (base | perm.mask()) : (base & ~perm.mask());
    }

    /** Every switch off: nobody but an operator may do anything in the territory. */
    public static int denyAll() {
        return 0;
    }

    /** Every switch on: the territory is decoration only, with no protection at all. */
    public static int allowAll() {
        int m = 0;
        for (AdminPerm p : values()) m |= p.mask();
        return m;
    }
}
