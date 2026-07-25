package top.leonx.territory.world;

/**
 * The kinds of interaction a claim can restrict.
 *
 * These names are a deliberate MIRROR of Easy Factions' {@code ChunkInteractionType}: the bridge converts
 * between the two with {@code ChunkInteractionType.valueOf(name())}, so the two enums must stay
 * name-for-name identical. Having our own copy is what keeps every class outside
 * {@code integration/} free of Easy Factions types.
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
    PLAYER_ATTACK
}
