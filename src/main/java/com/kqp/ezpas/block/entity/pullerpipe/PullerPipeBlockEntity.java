package com.kqp.ezpas.block.entity.pullerpipe;

import com.kqp.ezpas.block.ColoredPipeBlock;
import com.kqp.ezpas.block.FilteredPipeBlock;
import com.kqp.ezpas.block.PipeBlock;
import com.kqp.ezpas.block.entity.FilteredPipeBlockEntity;
import com.kqp.ezpas.block.pullerpipe.PullerPipeBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FacingBlock;
import net.minecraft.block.InventoryProvider;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Tickable;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Filter;
import java.util.stream.IntStream;

public abstract class PullerPipeBlockEntity extends BlockEntity implements Tickable {
    private List<ValidInventory> inventories;

    private int rrCounter;
    public int coolDown;

    public final int speed;
    public final int extractionRate;
    public final int subTickRate;

    public boolean loaded = false;

    public PullerPipeBlockEntity(BlockEntityType type, int speed, int extractionRate, int subTickRate) {
        super(type);

        inventories = new ArrayList();

        this.speed = speed;
        this.extractionRate = extractionRate;
        this.subTickRate = subTickRate;
    }

    @Override
    public void fromTag(BlockState bs, CompoundTag tag) {
        super.fromTag(bs, tag);
        this.rrCounter = tag.getInt("RoundRobinCounter");
        this.coolDown = tag.getInt("ExtractCoolDown");
    }

    @Override
    public CompoundTag toTag(CompoundTag tag) {
        super.toTag(tag);

        tag.putInt("RoundRobinCounter", rrCounter);
        tag.putInt("ExtractCoolDown", coolDown);

        return tag;
    }

    @Override
    public void tick() {
        if (!this.world.isClient) {
            if (!loaded) {
                this.resetSystem();

                loaded = true;
            }

            if (!this.world.isReceivingRedstonePower(pos)) {
                if (coolDown <= 0) {
                    for (int i = 0; i < subTickRate; i++) {
                        if (attemptExtract()) {
                            coolDown = speed;
                        } else {
                            break;
                        }
                    }
                } else {
                    coolDown = Math.max(0, coolDown - 1);
                }
            } else {
                coolDown = speed;
            }
        }
    }

    public boolean attemptExtract() {
        Direction facing = getFacing();
        Inventory from = getInventoryAt(world, this.pos.offset(facing));

        if (from != null && !from.isEmpty() && !inventories.isEmpty()) {
            if (rrCounter >= inventories.size()) {
                rrCounter = 0;
            }

            ValidInventory validInventory = inventories.get(rrCounter);
            Inventory to = getInventoryAt(world, validInventory.blockPos);

            if (to != null) {
                extract(from, to, facing.getOpposite(), validInventory.direction, validInventory.filters);

                rrCounter++;

                return true;
            } else {
                // This should never happen, but if it does we'll just retick

                inventories.remove(rrCounter);

                return attemptExtract();
            }
        }

        return false;
    }

    public void extract(Inventory from, Inventory to, Direction extractionSide, Direction insertSide, Filters filters) {
        // First find a stack to extract
        ItemStack extractionStack = null;
        int extractionSlot = -1;
        int insertionSlot = -1;

        // Iterate through available slots to extract from
        int[] availableExtractionSlots = getAvailableSlots(from, extractionSide);

        for (int i = 0; i < availableExtractionSlots.length; i++) {
            int queryExtractionSlot = availableExtractionSlots[i];
            ItemStack queryStack = from.getStack(queryExtractionSlot);

            if (queryStack != ItemStack.EMPTY
                    && filters.doesStackPass(queryStack)
                    && canExtract(from, queryExtractionSlot, queryStack, extractionSide)) {
                // Only continue if stack is not empty
                // Query the receiving inventory to see what slot it can be inserted into
                int queryInsertionSlot = getInsertionSlotForStack(to, queryStack, insertSide);

                if (queryInsertionSlot != -1) {
                    // If the slot is valid (!= -1) then set the appropriate fields, break, and extract and insert

                    extractionStack = queryStack;
                    extractionSlot = queryExtractionSlot;
                    insertionSlot = queryInsertionSlot;

                    break;
                }
            }
        }

        // Continue if all fields are valid
        if (extractionStack != null && extractionSlot != -1 && insertionSlot != -1) {
            ItemStack currentStackInSlot = to.getStack(insertionSlot);

            // The amount to extract is the minimum of the extraction rate and the get of the stack to extract
            int amountToExtract = Math.min(extractionRate, extractionStack.getCount());

            if (currentStackInSlot == ItemStack.EMPTY || currentStackInSlot.getCount() == 0) {
                // If current stack is empty, just replace it

                to.setStack(insertionSlot, from.removeStack(extractionSlot, amountToExtract));
            } else {
                // Calculate the new amount if the extraction and insertion goes through
                int newInsertionStackCount = currentStackInSlot.getCount() + amountToExtract;
                int maxCount = currentStackInSlot.getMaxCount();

                if (newInsertionStackCount <= currentStackInSlot.getMaxCount()) {
                    // If the new count is below the max, just set current stack to max and decrement from extraction stack

                    currentStackInSlot.setCount(newInsertionStackCount);
                    extractionStack.decrement(amountToExtract);
                } else {
                    // If there is overfill, set current stack to max and then resolve extraction amount

                    currentStackInSlot.setCount(maxCount);

                    int newExtractionStackCount = extractionStack.getCount(); // Get current count
                    newExtractionStackCount -= amountToExtract; // Simulate extraction
                    newExtractionStackCount += newInsertionStackCount - maxCount; // Return overflow

                    extractionStack.setCount(newExtractionStackCount);
                }
            }

            sanitizeSlot(from, extractionSlot);
            sanitizeSlot(to, insertionSlot);
        }
    }

    /**
     * Clears the list of connected inventories and updates it by recursively searching connected pipe blocks.
     */
    public void resetSystem() {
        inventories.clear();

        Direction facing = getFacing();

        BlockPos immediateBlockPos = this.pos.offset(facing.getOpposite());

        // Init searched set and then add the extracted block position to prevent weird behavior
        Set<BlockPos> searched = new HashSet();
        searched.add(this.pos.offset(facing));

        searchPipeBlock(immediateBlockPos, facing.getOpposite(), searched);
    }

    /**
     * Recursively searches for connected inventories through matching pipe blocks.
     * <p>
     * TODO: find a way to combine this with {@link PullerPipeBlockEntity#resetSystem(WorldAccess, BlockPos, Direction, Set)}
     *
     * @param blockPos  Current block position to search
     * @param direction The direction in which the current block pos was searched from
     * @param searched  Set of block positions that have already been searched
     */
    private void searchPipeBlock(BlockPos blockPos, Direction direction, Set<BlockPos> searched) {
        if (!searched.contains(blockPos)) {
            Block queryBlock = world.getBlockState(blockPos).getBlock();
            Block prevBlock = world.getBlockState(blockPos.offset(direction.getOpposite())).getBlock();

            boolean validPipeBranch = queryBlock instanceof PipeBlock;

            if (prevBlock instanceof ColoredPipeBlock && queryBlock instanceof ColoredPipeBlock) {
                validPipeBranch = (prevBlock == queryBlock);
            }

            if (validPipeBranch) {
                searched.add(blockPos);

                for (int i = 0; i < Direction.values().length; i++) {
                    Direction searchDirection = Direction.values()[i];

                    searchPipeBlock(blockPos.offset(searchDirection), searchDirection, searched);
                }
            } else if (getInventoryAt(world, blockPos) != null) {
                ValidInventory validInventory = new ValidInventory(blockPos, direction.getOpposite());
                BlockPos prevPos = blockPos.offset(direction.getOpposite());
                BlockEntity be = world.getBlockEntity(prevPos);

                if (be instanceof FilteredPipeBlockEntity) {
                    FilteredPipeBlockEntity filteredPipeBlockEntity = (FilteredPipeBlockEntity) be;
                    FilteredPipeBlock.Type type = ((FilteredPipeBlock) world.getBlockState(prevPos).getBlock()).type;

                    validInventory.addFrom(filteredPipeBlockEntity, type);
                }

                inventories.add(validInventory);
            }
        }
    }

    private Direction getFacing() {
        BlockState pullerPipe = this.world.getBlockState(this.pos);
        return pullerPipe.get(FacingBlock.FACING);
    }

    public List<ValidInventory> getValidInventories() {
        return inventories;
    }

    private static void sanitizeSlot(Inventory inv, int slot) {
        ItemStack stackInSlot = inv.getStack(slot);

        if (stackInSlot.getItem() == Items.AIR || stackInSlot.getCount() == 0) {
            inv.setStack(slot, ItemStack.EMPTY);
        }
    }

    public static Inventory getInventoryAt(World world, BlockPos blockPos) {
        Inventory inventory = null;
        BlockState blockState = world.getBlockState(blockPos);
        Block block = blockState.getBlock();

        if (block instanceof InventoryProvider) {
            inventory = ((InventoryProvider) block).getInventory(blockState, world, blockPos);
        } else if (block.hasBlockEntity()) {
            BlockEntity blockEntity = world.getBlockEntity(blockPos);

            if (blockEntity instanceof Inventory) {
                inventory = (Inventory) blockEntity;

                if (inventory instanceof ChestBlockEntity && block instanceof ChestBlock) {
                    inventory = ChestBlock.getInventory((ChestBlock) block, blockState, world, blockPos, true);
                }
            }
        }

        return inventory;
    }

    /**
     * Returns whether or not the slot and item stack can be extracted from the inventory from a given side.
     *
     * @param inv       The inventory
     * @param slot      The slot
     * @param itemStack The item stack
     * @param side      The side
     * @return True if it can be extracted
     */
    private static boolean canExtract(Inventory inv, int slot, ItemStack itemStack, Direction side) {
        if (inv instanceof SidedInventory) {
            return ((SidedInventory) inv).canExtract(slot, itemStack, side);
        } else {
            return true;
        }
    }

    /**
     * Attempts to find a valid slot for a given item stack to be inserted into the given inventory and side.
     *
     * @param inv   Inventory to insert into
     * @param stack Item Stack to insert into inventory
     * @param side  Side to insert into
     * @return -1 if no slot could be found
     */
    private static int getInsertionSlotForStack(Inventory inv, ItemStack stack, Direction side) {
        int[] availableSlots = getAvailableSlots(inv, side);

        for (int slot : availableSlots) {
            if (inv instanceof SidedInventory) {
                if (((SidedInventory) inv).canInsert(slot, stack, side)) {
                    ItemStack queryStack = inv.getStack(slot);

                    // canInsert doesn't check for item parity
                    if (queryStack == ItemStack.EMPTY || queryStack.getCount() == 0
                            || (queryStack.getCount() < queryStack.getMaxCount() && ItemStack.areItemsEqual(queryStack, stack) && ItemStack.areTagsEqual(queryStack, stack))) {
                        return slot;
                    }
                }
            } else {
                ItemStack queryStack = inv.getStack(slot);
                if (queryStack == ItemStack.EMPTY || queryStack.getCount() == 0
                        || (queryStack.getCount() < queryStack.getMaxCount() && ItemStack.areItemsEqual(queryStack, stack) && ItemStack.areTagsEqual(queryStack, stack))) {
                    return slot;
                }
            }
        }

        return -1;
    }

    private static int[] getAvailableSlots(Inventory inventory, Direction side) {
        return inventory instanceof SidedInventory ? ((SidedInventory) inventory).getAvailableSlots(side) : IntStream.range(0, inventory.size()).toArray();
    }

    /**
     * This method is called when searching for puller pipe blocks to update.
     *
     * @param world     World
     * @param blockPos  BlockPos to search
     * @param searched  Set of block positions already searched
     */
    public static void resetSystem(WorldAccess world, BlockPos blockPos, Direction direction, Set<BlockPos> searched) {
        if (!searched.contains(blockPos)) {
            searched.add(blockPos);

            Block queryBlock = world.getBlockState(blockPos).getBlock();
            Block prevBlock = world.getBlockState(blockPos.offset(direction.getOpposite())).getBlock();

            boolean validPipeBranch = queryBlock instanceof PipeBlock;

            if (prevBlock instanceof ColoredPipeBlock && queryBlock instanceof ColoredPipeBlock) {
                validPipeBranch = (prevBlock == queryBlock);
            }

            if (validPipeBranch) {
                for (int i = 0; i < Direction.values().length; i++) {
                    Direction searchDirection = Direction.values()[i];

                    resetSystem(world, blockPos.offset(searchDirection), searchDirection, searched);
                }
            } else if (queryBlock instanceof PullerPipeBlock) {
                // If we run into a puller block, RESET THE SYSTEM

                BlockEntity be = world.getBlockEntity(blockPos);

                if (be instanceof PullerPipeBlockEntity) {
                    ((PullerPipeBlockEntity) be).resetSystem();
                }
            }
        }
    }

    public static class ValidInventory {
        public final BlockPos blockPos;
        public final Direction direction;
        public final Filters filters;

        public ValidInventory(BlockPos blockPos, Direction direction) {
            this.blockPos = blockPos;
            this.direction = direction;
            this.filters = new Filters();
        }

        public void addFrom(FilteredPipeBlockEntity filteredPipeBlockEntity, FilteredPipeBlock.Type type) {
            Set<ComparableItemStack> addTo = type == FilteredPipeBlock.Type.WHITELIST ? filters.whitelist : filters.blacklist;

            for (int i = 0; i < filteredPipeBlockEntity.size(); i++) {
                ItemStack queryStack = filteredPipeBlockEntity.getStack(i);

                if (!queryStack.isEmpty()) {
                    addTo.add(new ComparableItemStack(queryStack));
                }
            }
        }

        @Override
        public String toString() {
            return blockPos.toString() + ", into " + direction;
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockPos.getX(), blockPos.getY(), blockPos.getZ(), direction.ordinal());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ValidInventory
                    && blockPos.equals(((ValidInventory) obj).blockPos)
                    && direction.equals(((ValidInventory) obj).direction);
        }
    }

    public static class Filters {
        public final Set<ComparableItemStack> whitelist, blacklist;

        public Filters() {
            this.whitelist = new HashSet();
            this.blacklist = new HashSet();
        }

        public boolean doesStackPass(ItemStack itemStack) {
            ComparableItemStack comparable = new ComparableItemStack(itemStack);
            boolean onWhitelist = whitelist.contains(comparable);
            boolean onBlacklist = blacklist.contains(comparable);

            if (onBlacklist) {
                return false;
            } else if (!whitelist.isEmpty()) {
                return onWhitelist;
            } else {
                return true;
            }
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Filter
                    && whitelist.equals(((Filters) obj).whitelist)
                    && blacklist.equals(((Filters) obj).blacklist);
        }

        @Override
        public int hashCode() {
            return Objects.hash(whitelist, blacklist);
        }

        public static DefaultedList<ItemStack> toDefaultedList(Set<ComparableItemStack> set) {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(set.size(), ItemStack.EMPTY);
            for (ComparableItemStack comparableItemStack : set) {
                list.add(comparableItemStack.itemStack);
            }

            return list;
        }
    }

    public static class ComparableItemStack {
        public final ItemStack itemStack;

        public ComparableItemStack(ItemStack itemStack) {
            this.itemStack = itemStack;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ComparableItemStack
                    && ItemStack.areItemsEqual(itemStack, ((ComparableItemStack) obj).itemStack)
                    && ItemStack.areTagsEqual(itemStack, ((ComparableItemStack) obj).itemStack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemStack.getTag(), itemStack.getItem().getTranslationKey());
        }

        @Override
        public String toString() {
            return itemStack.toString();
        }
    }
}
