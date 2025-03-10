package io.github.togar2.pvp.feature.cooldown;

import io.github.togar2.pvp.feature.CombatFeature;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;

/**
 * Combat feature to manage a players item cooldown animation.
 */
public interface ItemCooldownFeature extends CombatFeature {
	ItemCooldownFeature NO_OP = new ItemCooldownFeature() {
		@Override
		public boolean hasCooldown(Player player, String group) {
			return false;
		}
		
		@Override
		public void setCooldown(Player player, String group, int ticks) {}
	};
	
	boolean hasCooldown(Player player, String group);
	
	void setCooldown(Player player, String group, int ticks);
}
