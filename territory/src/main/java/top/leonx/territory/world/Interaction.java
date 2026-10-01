package top.leonx.territory.world;

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
   CONTAINER,
   DOOR,
   PVP,
   MOUNT,
   UTILITY;

   public Interaction holdfastFactionsEquivalent() {
      return this == CONTAINER || this == DOOR || this == UTILITY ? RIGHT_CLICK_BLOCK : (this == PVP ? PLAYER_ATTACK : (this == MOUNT ? INTERACT_ENTITY : this));
   }

   public boolean isOutsiderToggle() {
      return this == DOOR || this == UTILITY;
   }
}
