package name.modid.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
	private void modifyResult(CallbackInfo ci) {
		ItemStack primaryItem = this.inputSlots.getItem(0);
		ItemStack sacrificeItem = this.inputSlots.getItem(1);
		ItemStack resultItem = this.resultSlots.getItem(0);

		int totalRepairCost = 0;
		int totalEnchantCost = 0;
		int totalRenameCost = 0;

		// cancel if anvil operation failed
		if (resultItem.isEmpty()) {
			return;
		}

		// get item enchantment lists
		ItemEnchantments primaryEnchantmentList = EnchantmentHelper.getEnchantmentsForCrafting(primaryItem);
		ItemEnchantments sacrificeEnchantmentList = EnchantmentHelper.getEnchantmentsForCrafting(sacrificeItem);
		ItemEnchantments resultEnchantmentList = EnchantmentHelper.getEnchantmentsForCrafting(resultItem);

		// if performing material repair
		// only costs experience on enchanted tools
		if (primaryItem.isValidRepairItem(sacrificeItem) && !resultEnchantmentList.isEmpty()) {
			totalRepairCost = this.repairItemCountCost;
		}

		// if applying from enchanted book (or similar)
		if (sacrificeItem.has(DataComponents.STORED_ENCHANTMENTS)) {
			// calculate enchantments applied from book
			for(Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(sacrificeItem).entrySet()) {
				Holder<Enchantment> sacrificeEnchantment = entry.getKey();
				int resultEnchantmentLevel = resultEnchantmentList.getLevel(sacrificeEnchantment);
				int sacrificeEnchantmentLevel = entry.getIntValue();
				Enchantment enchantmentData = sacrificeEnchantment.value();
				if (resultEnchantmentLevel > sacrificeEnchantmentLevel) {
					// enchanting cost for applicable enchantments is halved
					totalEnchantCost += (int)Math.ceil(
							(double)getEnchantmentCost(resultEnchantmentLevel, enchantmentData.getMaxLevel()) / 2
					);
				} else {
					// enchanting cost for wasted enchantments is 1
					totalEnchantCost += 1;
				}
			}
		}

		// if merging items
		if (primaryItem.is(sacrificeItem.getItem())) {
			int primaryToSacrificeEnchantCost = 0;
			int sacrificeToPrimaryEnchantCost = 0;

			// calculate cost of applying sacrifice enchantments to primary item
			// does so by seeing which enchantments were increased / added from primary to result
			for(Object2IntMap.Entry<Holder<Enchantment>> entry : resultEnchantmentList.entrySet()) {
				Holder<Enchantment> resultEnchantment = entry.getKey();
				int primaryEnchantmentLevel = primaryEnchantmentList.getLevel(resultEnchantment);
				int resultEnchantmentLevel = entry.getIntValue();
				Enchantment enchantmentData = resultEnchantment.value();
				if (resultEnchantmentLevel > primaryEnchantmentLevel) {
					sacrificeToPrimaryEnchantCost += getEnchantmentCost(resultEnchantmentLevel, enchantmentData.getMaxLevel());
				}
			}

			// calculate cost of applying primary enchantments to sacrifice item
			for(Object2IntMap.Entry<Holder<Enchantment>> entry : resultEnchantmentList.entrySet()) {
				Holder<Enchantment> resultEnchantment = entry.getKey();
				int sacrificeEnchantmentLevel = sacrificeEnchantmentList.getLevel(resultEnchantment);
				int resultEnchantmentLevel = entry.getIntValue();
				Enchantment enchantmentData = resultEnchantment.value();
				if (resultEnchantmentLevel > sacrificeEnchantmentLevel) {
					primaryToSacrificeEnchantCost += getEnchantmentCost(resultEnchantmentLevel, enchantmentData.getMaxLevel());
				}
			}

			int higherCost = Math.max(primaryToSacrificeEnchantCost, sacrificeToPrimaryEnchantCost);
			int lowerCost = Math.min(primaryToSacrificeEnchantCost, sacrificeToPrimaryEnchantCost);

			// total cost tends towards cheaper operation, but a powerful tool is still expensive to merge onto
			totalEnchantCost = lowerCost + higherCost / 2;
			// costs at least 1 level to repair enchanted tool
			if (totalEnchantCost < 1 && !resultEnchantmentList.isEmpty()) {
				totalEnchantCost = 1;
			}
		}

		// if renaming
		if (
			this.itemName != null && !StringUtil.isBlank(this.itemName) ?
			// if adding name
			!this.itemName.equals(primaryItem.getHoverName().getString()) :
			// if removing name
			primaryItem.has(DataComponents.CUSTOM_NAME)
		) {
			totalRenameCost = 1;
		}

		// only renaming does not incur enchantment penalties
		if (this.onlyRenaming) {
			totalRepairCost = 0;
			totalEnchantCost = 0;
		}

		// finalize
		this.cost.set(totalRepairCost + totalEnchantCost + totalRenameCost);
		this.broadcastChanges();
	}
	@Inject(method = "mayPickup", at = @At(value = "RETURN"), cancellable = true)
	private void mayPickup(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(player.hasInfiniteMaterials() || player.experienceLevel >= this.cost.get());
	}

	@Unique
	private static final Map<Integer, Map<Integer, Integer>> enchantmentMap = Map.of(
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