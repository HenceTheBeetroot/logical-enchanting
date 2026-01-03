package name.modid.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(AnvilMenu.class)
abstract class MenuMixin extends ItemCombinerMenu {
	public MenuMixin(@Nullable MenuType<?> menuType, int i, Inventory inventory, ContainerLevelAccess containerLevelAccess, ItemCombinerMenuSlotDefinition itemCombinerMenuSlotDefinition) {
		super(menuType, i, inventory, containerLevelAccess, itemCombinerMenuSlotDefinition);
	}

	@Shadow private int repairItemCountCost;
	@Shadow private @Nullable String itemName;
	@Shadow @Final private DataSlot cost;
	@Shadow private boolean onlyRenaming;
	@Shadow public static int calculateIncreasedRepairCost(int i) { return -1; };

	// main logic; recalculates exp cost just before anvil output calculation finishes
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
			totalRepairCost = 1;
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

				// add cost dependent on total enchanting
				totalEnchantCost += getEnchantmentCost(resultEnchantmentLevel, enchantmentData.getMaxLevel());

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

			// base cost is half of total level cost
			totalEnchantCost = totalEnchantCost / 2 + totalEnchantCost % 2;

			// added cost relates to cheaper operation
			totalEnchantCost += Math.min(primaryToSacrificeEnchantCost, sacrificeToPrimaryEnchantCost);
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

	// removes minimum cost requirement to take items from an anvil
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

	// allows easy lookup from the enchantment map
	@Unique
	private int getEnchantmentCost(int level, int maxLevel) {
		return enchantmentMap.get(maxLevel).get(level);
	}

	// prevents the level 40 check from running and removing output item
	@Redirect(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z"))
	private boolean disableTooExpensive(Player instance) {
		return true;
	}
}