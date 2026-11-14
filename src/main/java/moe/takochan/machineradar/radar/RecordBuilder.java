package moe.takochan.machineradar.radar;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEBasicTank;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import gregtech.api.util.GTRecipe;
import gregtech.common.tileentities.storage.MTEQuantumChest;
import gregtech.common.tileentities.storage.MTEQuantumTank;

/**
 * 唯一直接依赖 gregtech.* 的类。
 */
public final class RecordBuilder {

    private static final HatchRef[] NO_HATCHES = new HatchRef[0];
    private static final Map<Integer, ItemStack> ICON_CACHE = new HashMap<Integer, ItemStack>();

    private RecordBuilder() {}

    public static MachineRecord build(IGregTechTileEntity te, World world) {
        IMetaTileEntity imte = te.getMetaTileEntity();
        if (!(imte instanceof MetaTileEntity)) return null;
        MetaTileEntity mte = (MetaTileEntity) imte;
        // 舱室是多方块的一部分，不是"单方块机器"。以前把它们也当单方块画，是画面舱室混乱的主因。
        if (mte instanceof MTEHatch) return null;

        MachineRecord r = new MachineRecord();
        r.dim = world.provider.dimensionId;
        r.x = te.getXCoord();
        r.y = te.getYCoord();
        r.z = te.getZCoord();
        r.id = te.getMetaTileID();
        try {
            r.allowed = te.isAllowedToWork();
        } catch (Throwable ignored) {}

        try {
            r.name = mte.getStackForm(1)
                .getDisplayName();
        } catch (Throwable ignored) {
            r.name = mte.getMetaName();
        }
        try {
            r.metaName = mte.getMetaName();
        } catch (Throwable ignored) {}
        r.icon = iconOf(mte, te);
        if (mte instanceof MTETieredMachineBlock) {
            r.tier = ((MTETieredMachineBlock) mte).mTier;
        }

        r.multi = mte instanceof MTEMultiBlockBase;
        r.progress = -1;

        int minX = r.x, minY = r.y, minZ = r.z;
        int maxX = r.x, maxY = r.y, maxZ = r.z;

        if (!r.multi) {
            r.assembled = true;
            r.hatches = NO_HATCHES;
            readBasicMachine(r, mte);
        } else {
            MTEMultiBlockBase mb = (MTEMultiBlockBase) mte;

            // 核心：先让 GT 用机器自己的锚点校验一次结构。
            // 校验过程中 StructureLib 会把每个结构单元的绝对坐标发给我们（见 StructureCapture），
            // 同时 GT 会 clearHatches() 并重新登记舱室，所以后面读到的舱室列表也是最新的。
            int[][] blocks = StructureCapture.capture(mb, te, world);

            r.assembled = mb.mMachine;
            r.eut = mb.mEUt;
            r.maxProgressTicks = mb.mMaxProgresstime;
            r.progressTicks = mb.mProgresstime;
            if (mb.mMaxProgresstime > 0) {
                r.progress = Math.max(0, Math.min(1000, (int) ((long) mb.mProgresstime * 1000L / mb.mMaxProgresstime)));
            }
            readOutputs(r, mb.mOutputItems, mb.mOutputFluids);

            List<HatchRef> hs = new ArrayList<HatchRef>(16);
            addTyped(hs, mb.mInputBusses, HatchRef.ITEM_IN);
            addTyped(hs, mb.mOutputBusses, HatchRef.ITEM_OUT);
            addTyped(hs, mb.mInputHatches, HatchRef.FLUID_IN);
            addTyped(hs, mb.mOutputHatches, HatchRef.FLUID_OUT);
            addTyped(hs, mb.mEnergyHatches, HatchRef.ENERGY_IN);
            addTyped(hs, mb.mDynamoHatches, HatchRef.ENERGY_OUT);
            addTyped(hs, mb.mMaintenanceHatches, HatchRef.MAINTENANCE);
            addTyped(hs, mb.mMufflerHatches, HatchRef.MUFFLER);
            addAny(hs, mb.mDualInputHatches, HatchRef.ME);
            addReflective(hs, mb);

            r.hatches = hs.toArray(NO_HATCHES);
            for (HatchRef h : r.hatches) {
                if (h.x < minX) minX = h.x;
                if (h.y < minY) minY = h.y;
                if (h.z < minZ) minZ = h.z;
                if (h.x > maxX) maxX = h.x;
                if (h.y > maxY) maxY = h.y;
                if (h.z > maxZ) maxZ = h.z;

                if (h.kind == HatchRef.ENERGY_IN) {
                    r.maxEuInput += h.maxEu * Math.max(1, h.amps);
                    r.maxAmps += Math.max(1, h.amps);
                }
                if (h.kind == HatchRef.MAINTENANCE) {
                    r.hasMaintenance = true;
                    r.maintenanceMissing = Math.max(r.maintenanceMissing, h.missing);
                }
            }
            if (r.tier < 0) {
                int best = -1;
                for (HatchRef h : r.hatches) {
                    if (h.kind == HatchRef.ENERGY_IN || h.kind == HatchRef.ENERGY_OUT) {
                        if (h.tier > best) best = h.tier;
                    }
                }
                if (best < 0) {
                    for (HatchRef h : r.hatches) if (h.tier > best) best = h.tier;
                }
                r.tier = (byte) best;
            }

            if (blocks != null && blocks.length > 0) {
                r.blocks = blocks;
                r.blockCount = blocks.length;
                int[] bb = bboxOf(blocks);
                minX = bb[0];
                minY = bb[1];
                minZ = bb[2];
                maxX = bb[3];
                maxY = bb[4];
                maxZ = bb[5];
                r.bboxExact = true;
            } else {
                // 未成型 / 采集失败：退回「控制器 + 舱室外扩一格」，只做定位用，不冒充结构。
                r.bboxError = StructureCapture.lastError();
                minX--;
                minY--;
                minZ--;
                maxX++;
                maxY++;
                maxZ++;
            }
        }

        r.minX = minX;
        r.minY = minY;
        r.minZ = minZ;
        r.maxX = maxX;
        r.maxY = maxY;
        r.maxZ = maxZ;

        // 存储机器（量子箱/量子缸）的内容 + 当前配方摘要
        readStorage(r, mte);
        if (r.maxProgressTicks > 0) {
            readRecipe(r, mte);
            if (r.recipe == null && r.multi) readMultiRecipeFallback(r, (MTEMultiBlockBase) mte);
        }
        return r;
    }

    // ------------------------------------------------------------------ 配方 / 存储


    // ------------------------------------------------------------------ 配方 / 存储

    private static final int MAX_STACKS = MachineRecord.RecipeSummary.MAX_STACKS;
    private static final Map<Class<?>, Field> RECIPE_FIELDS = new HashMap<Class<?>, Field>();
    private static final Set<Class<?>> NO_RECIPE_FIELD = new HashSet<Class<?>>();
    /** 配方对象在机器里是长期复用的，按对象缓存摘要，避免每秒重复拼显示名 */
    private static final Map<GTRecipe, MachineRecord.RecipeSummary> RECIPE_CACHE = new java.util.IdentityHashMap<GTRecipe, MachineRecord.RecipeSummary>();
    private static final int RECIPE_CACHE_MAX = 512;

    /**
     * 单方块存储：量子箱 / 量子缸。多方块的超级箱/缸属于多方块，不在"小机器"这里读。
     */
    private static void readStorage(MachineRecord r, MetaTileEntity mte) {
        try {
            if (mte instanceof MTEQuantumChest) {
                MTEQuantumChest q = (MTEQuantumChest) mte;
                MachineRecord.StorageInfo s = new MachineRecord.StorageInfo();
                s.chest = true;
                ItemStack stored = q.getItemStack();
                if (stored != null) s.itemName = stored.getDisplayName();
                s.itemCount = q.getItemCount();
                s.itemCapacity = q.getMaxItemCount();
                r.storage = s;
            } else if (mte instanceof MTEQuantumTank) {
                MTEQuantumTank t = (MTEQuantumTank) mte;
                MachineRecord.StorageInfo s = new MachineRecord.StorageInfo();
                s.tank = true;
                if (t.mFluid != null) {
                    s.fluidName = t.mFluid.getLocalizedName();
                    s.fluidAmount = t.mFluid.amount;
                }
                FluidTankInfo[] info = t.getTankInfo(ForgeDirection.UNKNOWN);
                if (info != null && info.length > 0) s.fluidCapacity = info[0].capacity;
                r.storage = s;
            }
        } catch (Throwable ignored) {}
    }

    /** 单方块机器的 mLastRecipe 是 protected，按类缓存 Field 反射读；拿到就填完整配方 */
    private static void readRecipe(MachineRecord r, MetaTileEntity mte) {
        GTRecipe rec = lastRecipe(mte);
        if (rec == null) return;

        MachineRecord.RecipeSummary cached = RECIPE_CACHE.get(rec);
        if (cached != null) {
            r.recipe = cached;
            return;
        }

        MachineRecord.RecipeSummary s = new MachineRecord.RecipeSummary();
        s.durationTicks = rec.mDuration;
        s.eut = rec.mEUt;
        fillItems(s, rec.mInputs, true);
        fillItems(s, rec.mOutputs, false);
        fillFluids(s, rec.mFluidInputs, true);
        fillFluids(s, rec.mFluidOutputs, false);
        if (RECIPE_CACHE.size() >= RECIPE_CACHE_MAX) RECIPE_CACHE.clear();
        RECIPE_CACHE.put(rec, s);
        r.recipe = s;
    }

    /** 多方块没有 mLastRecipe：退回"当前输出 + 时长 + EU"，输入未知 */
    private static void readMultiRecipeFallback(MachineRecord r, MTEMultiBlockBase mb) {
        MachineRecord.RecipeSummary s = new MachineRecord.RecipeSummary();
        s.durationTicks = mb.mMaxProgresstime;
        s.eut = mb.mEUt;
        fillItems(s, mb.mOutputItems, false);
        fillFluids(s, mb.mOutputFluids, false);
        if (s.hasOutputs()) r.recipe = s;
    }

    private static GTRecipe lastRecipe(MetaTileEntity mte) {
        Class<?> c = mte.getClass();
        Field f = RECIPE_FIELDS.get(c);
        if (f == null) {
            if (NO_RECIPE_FIELD.contains(c)) return null;
            try {
                Class<?> k = c;
                while (k != null && k != Object.class && f == null) {
                    try {
                        f = k.getDeclaredField("mLastRecipe");
                    } catch (NoSuchFieldException e) {
                        k = k.getSuperclass();
                    }
                }
                if (f == null) {
                    NO_RECIPE_FIELD.add(c);
                    return null;
                }
                f.setAccessible(true);
                RECIPE_FIELDS.put(c, f);
            } catch (Throwable t) {
                NO_RECIPE_FIELD.add(c);
                return null;
            }
        }
        try {
            Object v = f.get(mte);
            return v instanceof GTRecipe ? (GTRecipe) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fillItems(MachineRecord.RecipeSummary s, ItemStack[] stacks, boolean input) {
        if (stacks == null || stacks.length == 0) return;
        int cap = Math.min(MAX_STACKS, stacks.length);
        String[] names = new String[cap];
        long[] amounts = new long[cap];
        int n = 0;
        for (int i = 0; i < stacks.length && n < cap; i++) {
            ItemStack st = stacks[i];
            if (st == null) continue;
            names[n] = st.getDisplayName();
            amounts[n] = st.stackSize;
            n++;
        }
        if (n == 0) return;
        if (n < cap) {
            names = Arrays.copyOf(names, n);
            amounts = Arrays.copyOf(amounts, n);
        }
        if (input) {
            s.inItemNames = names;
            s.inItemAmounts = amounts;
        } else {
            s.outItemNames = names;
            s.outItemAmounts = amounts;
        }
    }

    private static void fillFluids(MachineRecord.RecipeSummary s, FluidStack[] fluids, boolean input) {
        if (fluids == null || fluids.length == 0) return;
        int cap = Math.min(MAX_STACKS, fluids.length);
        String[] names = new String[cap];
        long[] amounts = new long[cap];
        int n = 0;
        for (int i = 0; i < fluids.length && n < cap; i++) {
            FluidStack fs = fluids[i];
            if (fs == null) continue;
            names[n] = fs.getLocalizedName();
            amounts[n] = fs.amount;
            n++;
        }
        if (n == 0) return;
        if (n < cap) {
            names = Arrays.copyOf(names, n);
            amounts = Arrays.copyOf(amounts, n);
        }
        if (input) {
            s.inFluidNames = names;
            s.inFluidAmounts = amounts;
        } else {
            s.outFluidNames = names;
            s.outFluidAmounts = amounts;
        }
    }

    private static ItemStack iconOf(MetaTileEntity mte, IGregTechTileEntity te) {
        int id;
        try {
            id = te.getMetaTileID();
        } catch (Throwable t) {
            return null;
        }
        ItemStack cached = ICON_CACHE.get(Integer.valueOf(id));
        if (cached != null) return cached;
        ItemStack stack = null;
        try {
            stack = mte.getStackForm(1);
            if (stack != null) stack = stack.copy();
        } catch (Throwable ignored) {}
        if (stack != null) ICON_CACHE.put(Integer.valueOf(id), stack);
        return stack;
    }

    private static int[] bboxOf(int[][] blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < blocks.length; i++) {
            int[] b = blocks[i];
            if (b[0] < minX) minX = b[0];
            if (b[1] < minY) minY = b[1];
            if (b[2] < minZ) minZ = b[2];
            if (b[0] > maxX) maxX = b[0];
            if (b[1] > maxY) maxY = b[1];
            if (b[2] > maxZ) maxZ = b[2];
        }
        return new int[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    private static void readBasicMachine(MachineRecord r, MetaTileEntity mte) {
        if (!(mte instanceof MTEBasicMachine)) return;
        MTEBasicMachine bm = (MTEBasicMachine) mte;
        r.eut = bm.mEUt;
        r.maxProgressTicks = bm.mMaxProgresstime;
        r.progressTicks = bm.mProgresstime;
        if (bm.mMaxProgresstime > 0) {
            r.progress = Math.max(0, Math.min(1000, (int) ((long) bm.mProgresstime * 1000L / bm.mMaxProgresstime)));
        }
        readOutputs(r, bm.mOutputItems, new FluidStack[] { bm.mOutputFluid });
    }

    private static void readOutputs(MachineRecord r, ItemStack[] items, FluidStack[] fluids) {
        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                ItemStack s = items[i];
                if (s == null) continue;
                r.outputName = s.getDisplayName();
                r.outputAmount = s.stackSize;
                try {
                    r.outputIcon = s.copy();
                } catch (Throwable ignored) {}
                return;
            }
        }
        if (fluids != null) {
            for (int i = 0; i < fluids.length; i++) {
                FluidStack f = fluids[i];
                if (f == null) continue;
                r.outputName = f.getLocalizedName();
                r.outputAmount = f.amount;
                return;
            }
        }
    }

    private static final Map<Class<?>, Field[]> LIST_FIELDS = new HashMap<Class<?>, Field[]>();

    private static void addReflective(List<HatchRef> out, MTEMultiBlockBase mb) {
        Set<Long> seen = new HashSet<Long>();
        for (int i = 0; i < out.size(); i++) {
            HatchRef h = out.get(i);
            seen.add(pack(h.x, h.y, h.z));
        }

        Class<?> c = mb.getClass();
        while (c != null && c != Object.class) {
            Field[] fields = LIST_FIELDS.get(c);
            if (fields == null) {
                List<Field> found = new ArrayList<Field>();
                try {
                    Field[] declared = c.getDeclaredFields();
                    for (int i = 0; i < declared.length; i++) {
                        Field f = declared[i];
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (!List.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            found.add(f);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                fields = found.toArray(new Field[0]);
                LIST_FIELDS.put(c, fields);
            }

            for (int i = 0; i < fields.length; i++) {
                Object value;
                try {
                    value = fields[i].get(mb);
                } catch (Throwable t) {
                    continue;
                }
                if (!(value instanceof List)) continue;
                List<?> list = (List<?>) value;
                for (int j = 0; j < list.size(); j++) {
                    Object o = list.get(j);
                    if (!(o instanceof MTEHatch)) continue;
                    MTEHatch hatch = (MTEHatch) o;
                    IGregTechTileEntity b = hatch.getBaseMetaTileEntity();
                    if (b == null) continue;
                    if (!seen.add(pack(b.getXCoord(), b.getYCoord(), b.getZCoord()))) continue;
                    HatchRef ref = describe(hatch, kindFor(hatch));
                    if (ref != null) out.add(ref);
                }
            }
            c = c.getSuperclass();
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    private static int kindFor(MTEHatch hatch) {
        String n = hatch.getClass()
            .getSimpleName();
        if (n.contains("CokeOven")) return HatchRef.COKE;
        if (n.contains("Energy")) return HatchRef.ENERGY_IN;
        if (n.contains("Dynamo")) return HatchRef.ENERGY_OUT;
        if (n.contains("InputBus")) return HatchRef.ITEM_IN;
        if (n.contains("OutputBus")) return HatchRef.ITEM_OUT;
        if (n.contains("Maintenance")) return HatchRef.MAINTENANCE;
        if (n.contains("Muffler")) return HatchRef.MUFFLER;
        if (n.contains("Input")) return HatchRef.FLUID_IN;
        if (n.contains("Output")) return HatchRef.FLUID_OUT;
        return HatchRef.OTHER;
    }

    private static void addTyped(List<HatchRef> out, List<? extends MTEHatch> list, int kind) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            MTEHatch hatch = list.get(i);
            if (hatch == null) continue;
            HatchRef ref = describe(hatch, kind);
            if (ref != null) out.add(ref);
        }
    }

    private static void addAny(List<HatchRef> out, List<?> list, int kind) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof MTEHatch)) continue;
            HatchRef ref = describe((MTEHatch) o, kind);
            if (ref != null) out.add(ref);
        }
    }

    private static HatchRef describe(MTEHatch hatch, int kind) {
        IGregTechTileEntity base = hatch.getBaseMetaTileEntity();
        if (base == null) return null;

        HatchRef ref = new HatchRef(base.getXCoord(), base.getYCoord(), base.getZCoord(), kind);
        try {
            ref.tier = hatch.mTier;
            ref.facing = (byte) base.getFrontFacing()
                .ordinal();
        } catch (Throwable ignored) {}

        try {
            switch (kind) {
                case HatchRef.ENERGY_IN:
                    if (hatch instanceof MTEHatchEnergy) {
                        MTEHatchEnergy e = (MTEHatchEnergy) hatch;
                        ref.capacity = e.maxEUStore();
                        ref.maxEu = e.maxEUInput();
                        ref.amps = (int) e.maxAmperesIn();
                        ref.content = e.maxEUInput() + "V";
                    }
                    break;
                case HatchRef.ENERGY_OUT:
                    if (hatch instanceof MTEHatchDynamo) {
                        MTEHatchDynamo d = (MTEHatchDynamo) hatch;
                        ref.capacity = d.maxEUStore();
                        ref.maxEu = d.maxEUOutput();
                        ref.amps = 1;
                        ref.content = d.maxEUOutput() + "V";
                    }
                    break;
                case HatchRef.FLUID_IN:
                case HatchRef.FLUID_OUT:
                    if (hatch instanceof MTEBasicTank) {
                        MTEBasicTank tank = (MTEBasicTank) hatch;
                        FluidStack fs = tank.getFluid();
                        if (fs != null) {
                            ref.amount = fs.amount;
                            ref.content = fs.getLocalizedName();
                        }
                        FluidTankInfo[] info = tank.getTankInfo(ForgeDirection.UNKNOWN);
                        if (info != null && info.length > 0) ref.capacity = info[0].capacity;
                    }
                    break;
                case HatchRef.MAINTENANCE:
                    if (hatch instanceof MTEHatchMaintenance) {
                        MTEHatchMaintenance m = (MTEHatchMaintenance) hatch;
                        int miss = 0;
                        if (!m.mWrench) miss++;
                        if (!m.mScrewdriver) miss++;
                        if (!m.mSoftMallet) miss++;
                        if (!m.mHardHammer) miss++;
                        if (!m.mSolderingTool) miss++;
                        if (!m.mCrowbar) miss++;
                        ref.missing = miss;
                        ref.content = m.mAuto ? "自动维护" : (miss == 0 ? "已维护" : "缺 " + miss + " 项");
                    }
                    break;
                case HatchRef.ITEM_IN:
                case HatchRef.ITEM_OUT:
                case HatchRef.COKE:
                case HatchRef.ME:
                case HatchRef.OTHER:
                    readInventory(ref, hatch);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {}
        return ref;
    }

    private static void readInventory(HatchRef ref, MTEHatch hatch) {
        ItemStack[] inv = hatch.mInventory;
        if (inv == null) return;
        ref.totalSlots = inv.length;
        long count = 0;
        int used = 0;
        for (int i = 0; i < inv.length; i++) {
            ItemStack stack = inv[i];
            if (stack == null) continue;
            used++;
            count += stack.stackSize;
            if (ref.icon == null) {
                ref.content = stack.getDisplayName();
                try {
                    ref.icon = stack.copy();
                } catch (Throwable ignored) {}
            }
        }
        ref.usedSlots = used;
        ref.amount = count;
    }
}