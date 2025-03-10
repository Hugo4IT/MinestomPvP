package io.github.togar2.pvp.feature.food;

import io.github.togar2.pvp.feature.CombatFeature;
import io.github.togar2.pvp.feature.FeatureType;
import io.github.togar2.pvp.feature.RegistrableFeature;
import io.github.togar2.pvp.feature.config.DefinedFeature;
import io.github.togar2.pvp.feature.config.FeatureConfiguration;
import io.github.togar2.pvp.feature.cooldown.ItemCooldownFeature;
import io.github.togar2.pvp.utils.HeightUtil;
import io.github.togar2.pvp.utils.PotionFlags;
import io.github.togar2.pvp.utils.ViewUtil;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerPreEatEvent;
import net.minestom.server.event.player.PlayerTickEvent;
import net.minestom.server.event.trait.EntityInstanceEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemComponent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.*;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.TimedPotion;
import net.minestom.server.registry.Registry;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla implementation of {@link FoodFeature}
 * <p>
 * This also includes eating of food items.
 */
public class VanillaFoodFeature implements FoodFeature, CombatFeature, RegistrableFeature {
	public static final DefinedFeature<VanillaFoodFeature> DEFINED = new DefinedFeature<>(
			FeatureType.FOOD, VanillaFoodFeature::new,
			FeatureType.ITEM_COOLDOWN
	);
	
	private final FeatureConfiguration configuration;
	
	private ItemCooldownFeature itemCooldownFeature;
	
	public VanillaFoodFeature(FeatureConfiguration configuration) {
		this.configuration = configuration;
	}
	
	@Override
	public void initDependencies() {
		this.itemCooldownFeature = configuration.get(FeatureType.ITEM_COOLDOWN);
	}
	
	@Override
	public void init(EventNode<EntityInstanceEvent> node) {
		node.addListener(PlayerPreEatEvent.class, event -> {
			if (event.getItemStack().material() != Material.MILK_BUCKET
					&& !event.getItemStack().has(ItemComponent.FOOD))
				return;
			
			Food foodComponent = event.getItemStack().get(ItemComponent.FOOD);
			
			if (foodComponent == null) {
				event.setCancelled(true);
				return;
			}
			
			// If the players hunger is full and the food is not always edible, cancel
			// For some reason vanilla doesn't say honey is always edible but just overrides the method to always consume it
			boolean alwaysEat = foodComponent.canAlwaysEat() || event.getItemStack().material() == Material.HONEY_BOTTLE;
			if (event.getPlayer().getGameMode() != GameMode.CREATIVE
					&& !alwaysEat && event.getPlayer().getFood() == 20) {
				event.setCancelled(true);
				return;
			}
			
			event.setEatingTime(getUseTime(event.getItemStack().material(), Objects.requireNonNull(event.getItemStack().get(ItemComponent.CONSUMABLE))));
		});
		
		node.addListener(PlayerFinishItemUseEvent.class, event -> {
			if (event.getItemStack().material() != Material.MILK_BUCKET
					&& !event.getItemStack().has(ItemComponent.CONSUMABLE))
				return;
			
			onFinishEating(event.getPlayer(), event.getItemStack(), event.getHand());
		});
		
		node.addListener(PlayerTickEvent.class, event -> {
			Player player = event.getPlayer();
			if (player.isSilent() || !player.isEating()) return;
			
			tickEatingSounds(player);
		});
	}
	
	protected void onFinishEating(Player player, ItemStack stack, PlayerHand hand) {
		this.eat(player, stack);
		
		Consumable component = stack.get(ItemComponent.CONSUMABLE);
		assert component != null;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		
		triggerEatingSound(player, stack.material());
		
		if (stack.material() != Material.MILK_BUCKET) {
			ViewUtil.viewersAndSelf(player).playSound(Sound.sound(
					SoundEvent.ENTITY_PLAYER_BURP, Sound.Source.PLAYER,
					0.5f, random.nextFloat() * 0.1f + 0.9f
			), player);
		}

		UseCooldown cooldown = stack.get(ItemComponent.USE_COOLDOWN);

		if (cooldown != null) {
			itemCooldownFeature.setCooldown(player, cooldown.cooldownGroup(), (int)(cooldown.seconds() * 20.0f));
		}
		
		List<ConsumeEffect> effectList = component.effects();
		
		for (ConsumeEffect effect : effectList) {
			switch (effect) {
				case ConsumeEffect.ApplyEffects applyEffects:
					if (random.nextFloat() < applyEffects.probability()) {
						for (CustomPotionEffect potionEffect : applyEffects.effects()) {
							player.addEffect(new Potion(
									potionEffect.id(), potionEffect.amplifier(),
									potionEffect.duration(),
									PotionFlags.create(
											potionEffect.isAmbient(),
											potionEffect.showParticles(),
											potionEffect.showIcon()
									)
							));
						}
					}

					break;
				case ConsumeEffect.ClearAllEffects clearAllEffects:
					player.clearEffects();

					break;
				case ConsumeEffect.PlaySound playSound:
					player.playSound(Sound.sound(playSound.sound().key(), Sound.Source.PLAYER, 1.0f, 1.0f));

					break;
				case ConsumeEffect.RemoveEffects removeEffects:
					var toRemove = new ArrayList<PotionEffect>();

					for (TimedPotion potion : player.getActiveEffects()) {
						if (removeEffects.effects().contains(potion.potion().effect())) {
							toRemove.add(potion.potion().effect());
						}
					}

					for (PotionEffect potionEffect : toRemove) {
						player.removeEffect(potionEffect);
					}

					break;
				case ConsumeEffect.TeleportRandomly teleportRandomly:
					Instance instance = player.getInstance();

					// Vanilla minecraft also makes 16 attempts
					int i = 0;
					for (; i < 16; i++) {
						Pos newPosition = player.getPosition().add(random.nextDouble(-0.5f * teleportRandomly.diameter(), 0.5f * teleportRandomly.diameter()));

						if (!instance.getBlock(newPosition.sub(0.0, 1.0, 0.0)).isSolid()) {
							OptionalInt height = HeightUtil.getHeight(instance, player.getPosition().x(), player.getPosition().z());

							if (height.isPresent()) {
								newPosition = newPosition.withY(height.getAsInt());
							} else {
								continue;
							}
						}


						if (!instance.getBlock(newPosition).isSolid() && !instance.getBlock(newPosition.add(0.0, 1.0, 0.0)).isSolid()) {
							player.teleport(newPosition);

							i = -1;
							break;
						}
					}

					// Teleportation attempts failed
					if (i == 16) {
						OptionalInt height = HeightUtil.getHeight(instance, player.getPosition().x(), player.getPosition().z());

						if (height.isPresent()) {
							player.teleport(player.getPosition().withY(height.getAsInt()));
						}
					}

					break;
				default:
					break;
			}
		}
		
		if (stack.has(ItemComponent.SUSPICIOUS_STEW_EFFECTS)) {
			SuspiciousStewEffects effects = stack.get(ItemComponent.SUSPICIOUS_STEW_EFFECTS);
			assert effects != null;
			for (SuspiciousStewEffects.Effect effect : effects.effects()) {
				player.addEffect(new Potion(effect.id(), (byte) 0, effect.durationTicks(), PotionFlags.defaultFlags()));
			}
		}
		
		ItemStack leftOver = stack.get(ItemComponent.USE_REMAINDER);

		if (leftOver == null || leftOver.isAir()) leftOver = getUsingConvertsTo(stack);

		if (player.getGameMode() != GameMode.CREATIVE) {
			if (leftOver != null && !leftOver.isAir()) {
				if (stack.amount() == 1) {
					player.setItemInHand(hand, leftOver);
				} else {
					player.setItemInHand(hand, stack.withAmount(stack.amount() - 1));
					player.getInventory().addItemStack(leftOver);
				}
			} else {
				player.setItemInHand(hand, stack.withAmount(stack.amount() - 1));
			}
		}
	}
	
	@Override
	public void addFood(Player player, int food, float saturation) {
		player.setFood(Math.min(food + player.getFood(), 20));
		player.setFoodSaturation(Math.min(player.getFoodSaturation() + saturation, player.getFood()));
	}
	
	@Override
	public void eat(Player player, int food, float saturationModifier) {
		addFood(player, food, (float) food * saturationModifier * 2.0f);
	}
	
	@Override
	public void eat(Player player, ItemStack stack) {
		Food foodComponent = stack.get(ItemComponent.FOOD);
		if (foodComponent == null) return;
		addFood(player, foodComponent.nutrition(), foodComponent.saturationModifier());
	}
	
	@Override
	public void applySaturationEffect(Player player, byte amplifier) {
		eat(player, amplifier + 1, 1.0f);
	}
	
	protected void tickEatingSounds(Player player) {
		ItemStack stack = player.getItemInHand(Objects.requireNonNull(player.getItemUseHand()));
		
		Consumable component = stack.get(ItemComponent.CONSUMABLE);
		if (component == null) return;
		
		long useTime = getUseTime(stack.material(), component);
		long usedTicks = player.getCurrentItemUseTime();
		long remainingUseTicks = useTime - usedTicks;
		
		boolean canTrigger = component.consumeTicks() < 32 || remainingUseTicks <= useTime - 7;
		boolean shouldTrigger = canTrigger && remainingUseTicks % 4 == 0;
		if (!shouldTrigger) return;
		
		triggerEatingSound(player, stack.material());
	}
	
	protected void triggerEatingSound(Player player, Material material) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		
		if (material == Material.HONEY_BOTTLE || material == Material.MILK_BUCKET) { // Drinking
			SoundEvent soundEvent = material == Material.HONEY_BOTTLE ?
					SoundEvent.ITEM_HONEY_BOTTLE_DRINK : SoundEvent.ENTITY_GENERIC_DRINK;
			player.getViewersAsAudience().playSound(Sound.sound(
					soundEvent, Sound.Source.PLAYER,
					0.5f, random.nextFloat() * 0.1f + 0.9f
			), player);
		} else { // Eating
			player.getViewersAsAudience().playSound(Sound.sound(
					SoundEvent.ENTITY_GENERIC_EAT, Sound.Source.PLAYER,
					0.5f + 0.5f * random.nextInt(2),
					(random.nextFloat() - random.nextFloat()) * 0.2f + 1.0f
			), player);
		}
	}
	
	protected static final ItemStack EMPTY_BUCKET = ItemStack.of(Material.BUCKET);
	protected static final ItemStack EMPTY_BOTTLE = ItemStack.of(Material.GLASS_BOTTLE);
	
	protected @Nullable ItemStack getUsingConvertsTo(ItemStack stack) {
		// Only applies to items of which this has not been defined in the registry
		if (stack.material() == Material.MILK_BUCKET) {
			return EMPTY_BUCKET;
		} else if (stack.material() == Material.HONEY_BOTTLE) {
			return EMPTY_BOTTLE;
		}
		
		return null;
	}
	
	protected int getUseTime(@NotNull Material material, @NotNull Consumable component) {
		if (material == Material.HONEY_BOTTLE) return 40;
		return component.consumeTicks();
	}
}
