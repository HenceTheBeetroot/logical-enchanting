package name.modid.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(AnvilMenu.class)
abstract class MainMixin extends ItemCombinerMenu {

	public MainMixin(@Nullable MenuType<?> menuType, int i, Inventory inventory, ContainerLevelAccess containerLevelAccess, ItemCombinerMenuSlotDefinition itemCombinerMenuSlotDefinition) {
		super(menuType, i, inventory, containerLevelAccess, itemCombinerMenuSlotDefinition);
	}

	@Shadow private int repairItemCountCost;
	@Shadow private @Nullable String itemName;
	@Shadow @Final private DataSlot cost;
	@Shadow private boolean onlyRenaming;
	@Shadow public static int calculateIncreasedRepairCost(int i) { return -1; };

	// @Inject before return because this didn't play nice when I tried to @Overwrite it :P
	@Inject(method = "createResult", at = @At(value = "RETURN"))
	private void createResult(CallbackInfo ci) {
		ItemStack primaryItem = this.inputSlots.getItem(0);
		this.onlyRenaming = false;
		this.cost.set(0);

		// if the primary item exists and can be processed by an anvil
		if (!primaryItem.isEmpty() && EnchantmentHelper.canStoreEnchantments(primaryItem)) {
			ItemStack sacrificeItem = this.inputSlots.getItem(1);
			ItemStack outputItem = primaryItem.copy();
			ItemEnchantments.Mutable outputEnchantmentListMutable = new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting(outputItem));
			this.repairItemCountCost = 0;
			int totalRepairCost = 0;
			int totalEnchantCost = 0;
			int totalRenameCost = 0;
			boolean sacrificeIsEnchantmentStorage = false;

			// merging
			if (!sacrificeItem.isEmpty()) {
				this.cost.set(getItemEnchantmentValue(primaryItem) + getItemEnchantmentValue(sacrificeItem));
				// if sacrifice item behaves like enchanted book
				sacrificeIsEnchantmentStorage = sacrificeItem.has(DataComponents.STORED_ENCHANTMENTS);

				// material repair
				if (outputItem.isDamageableItem() && primaryItem.isValidRepairItem(sacrificeItem)) {
					int durabilityPerItem = outputItem.getMaxDamage() / 4;

					int damageToRepair = Math.min(outputItem.getDamageValue(), durabilityPerItem);
					// if already full durability
					if (damageToRepair <= 0) {
						this.resultSlots.setItem(0, ItemStack.EMPTY);
						this.cost.set(0);
						return;
					}

					this.repairItemCountCost = Math.min(
							// number of sacrifice items to heal to full
							(int)Math.ceil((double)damageToRepair / durabilityPerItem),
							// number of sacrifice items available
							sacrificeItem.getCount()
					);
					// IMPORTANT: may be more than actual damage
					damageToRepair = this.repairItemCountCost * durabilityPerItem;
					outputItem.setDamageValue(Math.max(outputItem.getDamageValue() - damageToRepair, 0));

				// if not performing material repair
				} else {
					// reject invalid merges
					if (!sacrificeIsEnchantmentStorage && (!outputItem.is(sacrificeItem.getItem()) || !outputItem.isDamageableItem())) {
						this.resultSlots.setItem(0, ItemStack.EMPTY);
						this.cost.set(0);
						return;
					}

					// if merge can result in repair
					if (outputItem.isDamageableItem() && !sacrificeIsEnchantmentStorage) {
						int outputDamage = outputItem.getDamageValue();
						outputDamage -= sacrificeItem.getMaxDamage() - sacrificeItem.getDamageValue();
						outputDamage -= outputItem.getMaxDamage() * 12 / 100;
						outputDamage = Math.max(outputDamage, 0);

						if (outputDamage < outputItem.getDamageValue()) {
							outputItem.setDamageValue(outputDamage);
							totalRepairCost += 2;
						}
					}

					ItemEnchantments sacrificeEnchantmentList = EnchantmentHelper.getEnchantmentsForCrafting(sacrificeItem);
					boolean someEnchantPassed = false;
					boolean someEnchantFailed = false;

					// calculate new enchantments
					for(Object2IntMap.Entry<Holder<Enchantment>> entry : sacrificeEnchantmentList.entrySet()) {
						Holder<Enchantment> sacrificeEnchantment = (Holder)entry.getKey();
						int primaryEnchantmentLevel = outputEnchantmentListMutable.getLevel(sacrificeEnchantment);
						int sacrificeEnchantmentLevel = entry.getIntValue();
						int newEnchantmentLevel = Math.max(sacrificeEnchantmentLevel, primaryEnchantmentLevel);
						if (primaryEnchantmentLevel == sacrificeEnchantmentLevel) {
							newEnchantmentLevel++;
						}

						Enchantment enchantmentData = (Enchantment)sacrificeEnchantment.value();
						boolean canEnchant = enchantmentData.canEnchant(primaryItem);
						// override for creative players and from books
						if (this.player.hasInfiniteMaterials() || primaryItem.is(Items.ENCHANTED_BOOK)) {
							canEnchant = true;
						}

						for(Holder<Enchantment> primaryEnchantment : outputEnchantmentListMutable.keySet()) {
							// reject if any conflicting enchantments are found
							if (!primaryEnchantment.equals(sacrificeEnchantment) && !Enchantment.areCompatible(sacrificeEnchantment, primaryEnchantment)) {
								canEnchant = false;
							}
						}

						if (!canEnchant) {
							someEnchantFailed = true;
						} else {
							someEnchantPassed = true;
							// cap enchantment level
							if (newEnchantmentLevel > enchantmentData.getMaxLevel()) {
								newEnchantmentLevel = enchantmentData.getMaxLevel();
							}

							// store enchantment
							outputEnchantmentListMutable.set(sacrificeEnchantment, newEnchantmentLevel);

							// fail if multiple items in primary slot
							if (primaryItem.getCount() > 1) {
								this.resultSlots.setItem(0, ItemStack.EMPTY);
								this.cost.set(0);
								return;
							}
						}
					}

					if (!someEnchantPassed && someEnchantFailed) {
						this.resultSlots.setItem(0, ItemStack.EMPTY);
						this.cost.set(0);
						return;
					}
				}
			} else { this.onlyRenaming = true; }
			// renaming
			// if there is a name entered
			if (this.itemName != null && !StringUtil.isBlank(this.itemName)) {
				// if this is a different name
				if (!this.itemName.equals(primaryItem.getHoverName().getString())) {
					totalRenameCost = 1;
					outputItem.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
				}
				// if removing a name
			} else if (primaryItem.has(DataComponents.CUSTOM_NAME)) {
				totalRenameCost = 1;
				outputItem.remove(DataComponents.CUSTOM_NAME);
			}

			if (totalRepairCost == 0 && totalEnchantCost == 0) {
				this.onlyRenaming = true;
			}

			if (!outputItem.isEmpty()) {
				// preserve work penalty I guess
				int outputRepairCost = (Integer) outputItem.getOrDefault(DataComponents.REPAIR_COST, 0);
				if (outputRepairCost < (Integer) sacrificeItem.getOrDefault(DataComponents.REPAIR_COST, 0)) {
					outputRepairCost = (Integer) sacrificeItem.getOrDefault(DataComponents.REPAIR_COST, 0);
				}

				if (totalRepairCost > 0 || totalEnchantCost > 0) {
					outputRepairCost = calculateIncreasedRepairCost(outputRepairCost);
				}

				outputItem.set(DataComponents.REPAIR_COST, outputRepairCost);

				// set enchants
				EnchantmentHelper.setEnchantments(outputItem, outputEnchantmentListMutable.toImmutable());

				if (!sacrificeItem.isEmpty()) {
					// calculate cost from output item
					for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(outputItem).entrySet()) {
						// init vals
						Holder<Enchantment> enchantment = (Holder)entry.getKey();
						int enchantmentLevel = entry.getIntValue();
						Enchantment enchantmentData = (Enchantment) enchantment.value();



						// calculate cost
						int enchantmentCost = getEnchantmentCost(enchantmentLevel, enchantmentData.getMaxLevel());
						// halve cost if applying from enchantment storage
						if (sacrificeIsEnchantmentStorage) {
							enchantmentCost = Math.max(1, (int) Math.ceil((double) enchantmentCost / 2));
						}

						totalEnchantCost += enchantmentCost;
					}
				}

			}

			// finalize
			this.resultSlots.setItem(0, outputItem);
			this.cost.set(totalRepairCost + totalEnchantCost + totalRenameCost);
			this.broadcastChanges();
		// if the primary item is invalid
		} else {
			this.resultSlots.setItem(0, ItemStack.EMPTY);
			this.cost.set(0);
		}

//		// if the primary item exists and can be processed by an anvil
//		if (!primaryItem.isEmpty() && EnchantmentHelper.canStoreEnchantments(primaryItem)) {
//			int totalRepairCost = 0;
//			int totalEnchantCost = 0;
//			int totalRenameCost = 0;
//			// init sacrifice item
//			ItemStack sacrificeItem = this.inputSlots.getItem(1);
//			// init output item
//			ItemStack outputItem = primaryItem.copy();
//			// init enchantmentData list for output item
//			ItemEnchantments.Mutable outputEnchantmentListMutable = new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting(outputItem));
//			this.repairItemCountCost = 0;
//			// if the sacrifice item exists
//			if (!sacrificeItem.isEmpty()) {
//				// set cost to sum of individual costs
//				this.cost.set(getItemEnchantmentValue(primaryItem) + getItemEnchantmentValue(sacrificeItem));
//				// if sacrifice item is or behaves like an enchanted book
//				boolean sacrificeIsEnchantmentStorage = sacrificeItem.has(DataComponents.STORED_ENCHANTMENTS);
//				// if performing material repair
//				if (outputItem.isDamageableItem() && primaryItem.isValidRepairItem(sacrificeItem)) {
//					int durabilityPerItem = outputItem.getMaxDamage() / 4;
//
//					int damageToRepair = Math.min(outputItem.getDamageValue(), durabilityPerItem);
//					// if already full durability
//					if (damageToRepair <= 0) {
//						this.resultSlots.setItem(0, ItemStack.EMPTY);
//						this.cost.set(0);
//						return;
//					}
//
//					this.repairItemCountCost = Math.min(
//							// number of sacrifice items to heal to full
//							(int)Math.ceil((double)damageToRepair / durabilityPerItem),
//							// number of sacrifice items available
//							sacrificeItem.getCount()
//					);
//					// IMPORTANT: may be more than actual damage
//					damageToRepair = this.repairItemCountCost * durabilityPerItem;
//					outputItem.setDamageValue(Math.max(outputItem.getDamageValue() - damageToRepair, 0));
//
//				// if not performing material repair
//				} else {
//					// reject invalid merge repairs
//					if (!sacrificeIsEnchantmentStorage && (!outputItem.is(sacrificeItem.getItem()) || !outputItem.isDamageableItem())) {
//						this.resultSlots.setItem(0, ItemStack.EMPTY);
//						this.cost.set(0);
//						return;
//					}
//
//					// if merge repair (NOT enchantmentData combination)
//					if (outputItem.isDamageableItem() && !sacrificeIsEnchantmentStorage) {
//						int primaryDurability = primaryItem.getMaxDamage() - primaryItem.getDamageValue();
//						int sacrificeDurability = sacrificeItem.getMaxDamage() - sacrificeItem.getDamageValue();
//						int newPrimaryDurability = primaryDurability + sacrificeDurability + outputItem.getMaxDamage() * 12 / 100;
//						int outputDamage = outputItem.getMaxDamage() - newPrimaryDurability;
//						if (outputDamage < 0) {
//							outputDamage = 0;
//						}
//
//						if (outputDamage < outputItem.getDamageValue()) {
//							outputItem.setDamageValue(outputDamage);
//							totalRepairCost += 2;
//						}
//					}
//
//					ItemEnchantments sacrificeEnchantmentList = EnchantmentHelper.getEnchantmentsForCrafting(sacrificeItem);
//					boolean someEnchantPassed = false;
//					boolean someEnchantFailed = false;
//
//					for(Object2IntMap.Entry<Holder<Enchantment>> entry : sacrificeEnchantmentList.entrySet()) {
//						Holder<Enchantment> sacrificeEnchantment = (Holder)entry.getKey();
//						int primaryEnchantmentLevel = outputEnchantmentListMutable.getLevel(sacrificeEnchantment);
//						int sacrificeEnchantmentLevel = entry.getIntValue();
//						int newEnchantmentLevel = Math.max(sacrificeEnchantmentLevel, primaryEnchantmentLevel);
//						if (primaryEnchantmentLevel == sacrificeEnchantmentLevel) {
//							newEnchantmentLevel++;
//						}
//						Enchantment enchantmentData = (Enchantment) sacrificeEnchantment.value();
//						boolean canEnchant = enchantmentData.canEnchant(primaryItem);
//						// override for creative players and from books
//						if (this.player.hasInfiniteMaterials() || primaryItem.is(Items.ENCHANTED_BOOK)) {
//							canEnchant = true;
//						}
//
//						for(Holder<Enchantment> primaryEnchantment : outputEnchantmentListMutable.keySet()) {
//							// reject if any conflicting enchantments are found
//							if (!primaryEnchantment.equals(sacrificeEnchantment) && !Enchantment.areCompatible(sacrificeEnchantment, primaryEnchantment)) {
//								canEnchant = false;
//								totalEnchantCost += 1;
//							}
//						}
//
//						if (!canEnchant) {
//							someEnchantFailed = true;
//						} else {
//							someEnchantPassed = true;
//							// cap enchantment level
//							if (newEnchantmentLevel > enchantmentData.getMaxLevel()) {
//								newEnchantmentLevel = enchantmentData.getMaxLevel();
//							}
//
//							// store enchantment
//							outputEnchantmentListMutable.set(sacrificeEnchantment, newEnchantmentLevel);
//							int enchantmentCost = enchantmentData.getAnvilCost();
//							// halve cost if applying from enchantment storage
//							if (sacrificeIsEnchantmentStorage) {
//								enchantmentCost = Math.max(1, enchantmentCost / 2);
//							}
//
//							totalEnchantCost += enchantmentCost * newEnchantmentLevel;
//
//							// fail if multiple items in primary slot
//							if (primaryItem.getCount() > 1) {
//								this.resultSlots.setItem(0, ItemStack.EMPTY);
//								this.cost.set(0);
//								return;
//							}
//						}
//					}
//
//					// fail if all enchants failed
//					if (someEnchantFailed && !someEnchantPassed) {
//						this.resultSlots.setItem(0, ItemStack.EMPTY);
//						this.cost.set(0);
//						return;
//					}
//				}
//			}
//
//			// renaming
//			if (this.itemName != null && !StringUtil.isBlank(this.itemName)) {
//				if (!this.itemName.equals(primaryItem.getHoverName().getString())) {
//					totalRenameCost += 1;
//					outputItem.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
//				}
//			} else if (primaryItem.has(DataComponents.CUSTOM_NAME)) {
//				totalRenameCost += 1;
//				outputItem.remove(DataComponents.CUSTOM_NAME);
//			}
//
//			if (totalRenameCost <= 0) {
//				outputItem = ItemStack.EMPTY;
//			}
//
//			if (totalRepairCost == 0 && totalEnchantCost == 0) {
//				this.onlyRenaming = true;
//			}
//
//			if (!outputItem.isEmpty()) {
//				int outputRepairCost = (Integer) outputItem.getOrDefault(DataComponents.REPAIR_COST, 0);
//				if (outputRepairCost < (Integer) sacrificeItem.getOrDefault(DataComponents.REPAIR_COST, 0)) {
//					outputRepairCost = (Integer) sacrificeItem.getOrDefault(DataComponents.REPAIR_COST, 0);
//				}
//
//				if (totalRepairCost > 0 || totalEnchantCost > 0) {
//					outputRepairCost = calculateIncreasedRepairCost(outputRepairCost);
//				}
//
//				outputItem.set(DataComponents.REPAIR_COST, outputRepairCost);
//				EnchantmentHelper.setEnchantments(outputItem, outputEnchantmentListMutable.toImmutable());
//			}
//
//			this.resultSlots.setItem(0, outputItem);
//			this.broadcastChanges();
//
//		// if the primary item is invalid
//		} else {
//			this.resultSlots.setItem(0, ItemStack.EMPTY);
//			this.cost.set(0);
//		}
	}

	@Unique
	private int getItemEnchantmentValue(ItemStack item) {
		return item.getOrDefault(DataComponents.REPAIR_COST, 0);
	}

	@Unique
	private static Map<Integer, Map<Integer, Integer>> enchantmentMap = Map.of(
			5, Map.of(
					5, 7,
					4, 3,
					3, 2,
					2, 1,
					1, 1
			),
			4, Map.of(
					4, 4,
					3, 2,
					2, 1,
					1, 1
			),
			3, Map.of(
					3, 3,
					2, 2,
					1, 1
			),
			2, Map.of(
					2, 3,
					1, 2
			),
			1, Map.of(
					1, 4
			)
	);

	@Unique
	private int getEnchantmentCost(int level, int maxLevel) {
		return enchantmentMap.get(maxLevel).get(level);
	}
}