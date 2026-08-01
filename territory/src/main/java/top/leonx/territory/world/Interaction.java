package top.leonx.territory.world;

/**
 * The kinds of interaction a claim can restrict.
 *
 * Every value except {@link #CONTAINER} is a deliberate MIRROR of Easy Factions'
 * {@code ChunkInteractionType}: the bridge converts between the two with
 * {@code ChunkInteractionType.valueOf(name())}, so those names must stay identical. Having our own copy is
 * what keeps every class outside {@code integration/} free of Easy Factions types.
 */
public enum Interaction {
    BREAK_BLOCK,
    PLACE_BLOCK,
    RIGHT_CLICK_BLOCK,
    LEFT_CLICK_BLOCK,
    RIGHT_CLICK_ITEM,
    INTERACT_ENTITY,
    MOB_GRIEFING_DAMAGE,
    EXPLOSION_DAMAGE,
    PISTON_MOVE,
    USE_BUCKET,
    PLAYER_ATTACK,

    /**
     * Opening something that holds items: a chest, barrel, shulker, hopper, furnace, brewing stand.
     *
     * OURS, with no counterpart in Easy Factions, which sees only an undifferentiated RIGHT_CLICK_BLOCK.
     * Splitting it out is what lets a server open its claims up for walking around and using doors while
     * still deciding separately whether the chests inside are fair game.
     */
    CONTAINER;

    /**
     * The value Easy Factions would have used for this interaction, since its enum has no CONTAINER: to
     * Easy Factions, opening a chest is just another right click on a block.
     *
     * Used whenever we need to know what EASY FACTIONS decided about an interaction rather than what we
     * decided, which is the whole basis of un-cancelling its refusals.
     */
    public Interaction easyFactionsEquivalent() {
        return this == CONTAINER ? RIGHT_CLICK_BLOCK : this;
    }
}
