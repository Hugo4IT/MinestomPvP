package io.github.togar2.pvp.feature.cooldown;

import io.github.togar2.pvp.feature.FeatureType;
import io.github.togar2.pvp.feature.RegistrableFeature;
import io.github.togar2.pvp.feature.config.DefinedFeature;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerTickEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.event.trait.EntityInstanceEvent;
import net.minestom.server.item.ItemComponent;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.UseCooldown;
import net.minestom.server.network.packet.server.play.SetCooldownPacket;
import net.minestom.server.tag.Tag;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Vanilla implementation of {@link ItemCooldownFeature}
 */
public class VanillaItemCooldownFeature implements ItemCooldownFeature, RegistrableFeature {
	public static final DefinedFeature<VanillaItemCooldownFeature> DEFINED = new DefinedFeature<>(
			FeatureType.ITEM_COOLDOWN, configuration -> new VanillaItemCooldownFeature(),
			VanillaItemCooldownFeature::initPlayer
	);
	
	public static final Tag<Map<String, Long>> COOLDOWN_END = Tag.Transient("cooldownEnd");
	
	private static void initPlayer(Player player, boolean firstInit) {
		player.setTag(COOLDOWN_END, new HashMap<>());
	}
	
	@Override
	public int getPriority() {
		// Needs to stop every item usage event
		return -5;
	}
	
	@Override
	public void init(EventNode<EntityInstanceEvent> node) {
		node.addListener(PlayerTickEvent.class, event -> {
			Player player = event.getPlayer();
			Map<String, Long> cooldown = player.getTag(COOLDOWN_END);
			if (cooldown.isEmpty()) return;
			long time = System.currentTimeMillis();
			
			Iterator<Map.Entry<String, Long>> iterator = cooldown.entrySet().iterator();
			
			while (iterator.hasNext()) {
				Map.Entry<String, Long> entry = iterator.next();
				if (entry.getValue() <= time) {
					iterator.remove();
					sendCooldownPacket(player, entry.getKey(), 0);
				}
			}
		});
		
		node.addListener(PlayerUseItemEvent.class, event -> {
			UseCooldown cooldown = event.getItemStack().get(ItemComponent.USE_COOLDOWN);

			if (cooldown != null && hasCooldown(event.getPlayer(), cooldown.cooldownGroup()))
				event.setCancelled(true);
		});
	}
	
	@Override
	public boolean hasCooldown(Player player, String group) {
		Map<String, Long> cooldown = player.getTag(COOLDOWN_END);
		return cooldown.containsKey(group) && cooldown.get(group) > System.currentTimeMillis();
	}
	
	@Override
	public void setCooldown(Player player, String group, int ticks) {
		Map<String, Long> cooldown = player.getTag(COOLDOWN_END);
		cooldown.put(group, System.currentTimeMillis() + (long) ticks * MinecraftServer.TICK_MS);
		sendCooldownPacket(player, group, ticks);
	}
	
	protected void sendCooldownPacket(Player player, String group, int ticks) {
		player.getPlayerConnection().sendPacket(new SetCooldownPacket(group, ticks));
	}
}
