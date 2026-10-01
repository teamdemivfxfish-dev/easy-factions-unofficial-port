package top.leonx.territory.world;

import java.util.List;

public enum AdminPerm {
   BREAK(0, "break", Interaction.BREAK_BLOCK),
   PLACE(1, "place", Interaction.PLACE_BLOCK),
   USE(2, "use", Interaction.RIGHT_CLICK_BLOCK, Interaction.LEFT_CLICK_BLOCK, Interaction.RIGHT_CLICK_ITEM, Interaction.USE_BUCKET, Interaction.CONTAINER, Interaction.DOOR, Interaction.UTILITY),
   ENTITIES(3, "entities", Interaction.INTERACT_ENTITY, Interaction.MOUNT),
   ATTACK(4, "attack", Interaction.PLAYER_ATTACK, Interaction.PVP),
   DAMAGE(5, "damage", Interaction.EXPLOSION_DAMAGE, Interaction.MOB_GRIEFING_DAMAGE, Interaction.PISTON_MOVE);

   public static final int NOT_SET = -1;
   private final int bit;
   private final String key;
   private final List<Interaction> covers;

   private AdminPerm(int bit, String key, Interaction... covers) {
      this.bit = bit;
      this.key = key;
      this.covers = List.of(covers);
   }

   public int mask() {
      return 1 << this.bit;
   }

   public String langKey() {
      return "gui.territory.perm." + this.key;
   }

   public String descKey() {
      return "gui.territory.perm." + this.key + ".desc";
   }

   public List<Interaction> covers() {
      return this.covers;
   }

   public static AdminPerm forInteraction(Interaction interaction) {
      for (AdminPerm p : values()) {
         if (p.covers.contains(interaction)) {
            return p;
         }
      }

      return null;
   }

   public boolean allowedIn(int mask) {
      return (mask & this.mask()) != 0;
   }

   public static int with(int mask, AdminPerm perm, boolean allowed) {
      int base = mask == -1 ? 0 : mask;
      return allowed ? base | perm.mask() : base & ~perm.mask();
   }

   public static int denyAll() {
      return 0;
   }

   public static int allowAll() {
      int m = 0;

      for (AdminPerm p : values()) {
         m |= p.mask();
      }

      return m;
   }
}
