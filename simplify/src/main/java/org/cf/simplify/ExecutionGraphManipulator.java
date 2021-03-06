package org.cf.simplify;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.cf.smalivm.SideEffect;
import org.cf.smalivm.VirtualMachine;
import org.cf.smalivm.context.ExecutionContext;
import org.cf.smalivm.context.ExecutionGraph;
import org.cf.smalivm.context.ExecutionNode;
import org.cf.smalivm.context.MethodState;
import org.cf.smalivm.opcode.FillArrayDataPayloadOp;
import org.cf.smalivm.opcode.InvokeOp;
import org.cf.smalivm.opcode.NewInstanceOp;
import org.cf.smalivm.opcode.NopOp;
import org.cf.smalivm.opcode.Op;
import org.cf.smalivm.opcode.OpCreator;
import org.cf.smalivm.opcode.ReturnOp;
import org.cf.smalivm.opcode.ReturnVoidOp;
import org.cf.smalivm.opcode.SwitchPayloadOp;
import org.cf.smalivm.reference.LocalMethod;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.BuilderTryBlock;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MethodLocation;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.primitives.Ints;

public class ExecutionGraphManipulator extends ExecutionGraph {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(ExecutionGraphManipulator.class.getSimpleName());

    private final DexBuilder dexBuilder;
    private final MutableMethodImplementation implementation;
    private final LocalMethod localMethod;
    private final VirtualMachine vm;
    private final Set<MethodLocation> recreateLocations;
    private final List<MethodLocation> reexecuteLocations;
    private final OpCreator opCreator;
    private boolean recreateOrReexecute;

    public ExecutionGraphManipulator(ExecutionGraph graph, LocalMethod localMethod, VirtualMachine vm,
                    DexBuilder dexBuilder) {
        super(graph, true);

        this.dexBuilder = dexBuilder;
        this.localMethod = localMethod;
        implementation = localMethod.getImplementation();
        this.vm = vm;
        opCreator = getOpCreator(vm, addressToLocation);
        recreateLocations = new HashSet<MethodLocation>();

        // When ops are added, such as when unreflecting, need to execute in order to ensure
        // correct contexts for each op. Executing out of order may read registers that haven't been assigned yet.
        reexecuteLocations = new LinkedList<MethodLocation>();
        recreateOrReexecute = true;
    }

    public void addInstruction(MethodLocation location, BuilderInstruction instruction) {
        int index = location.getIndex();
        implementation.addInstruction(index, instruction);
        MethodLocation newLocation = instruction.getLocation();
        MethodLocation oldLocation = implementation.getInstructions().get(index + 1).getLocation();
        try {
            Method m = MethodLocation.class.getDeclaredMethod("mergeInto", MethodLocation.class);
            m.setAccessible(true);
            m.invoke(oldLocation, newLocation);
        } catch (Exception e) {
            log.error("Error invoking MethodLocation#mergeInto(). Wrong dexlib version?", e);
        }

        rebuildGraph();
    }

    public void addInstruction(int address, BuilderInstruction newInstruction) {
        addInstruction(getLocation(address), newInstruction);
    }

    private int getRegisterCount(int address) {
        return getNodePile(address).get(0).getContext().getMethodState().getRegisterCount();
    }

    public int[] getAvailableRegisters(int address) {
        int[] registers = new int[getRegisterCount(address)];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = i;
        }

        Deque<ExecutionNode> stack = new ArrayDeque<ExecutionNode>(getChildren(address));
        ExecutionNode node = stack.peek();
        if (null == node) {
            // No children. All registers available!
            assert getTemplateNode(address).getOp() instanceof ReturnOp || getTemplateNode(address).getOp() instanceof ReturnVoidOp;
            return registers;
        }

        Set<Integer> registersRead = new HashSet<Integer>();
        Set<Integer> registersAssigned = new HashSet<Integer>();
        while ((node = stack.poll()) != null) {
            MethodState mState = node.getContext().getMethodState();
            for (Integer register : registers) {
                if (registersRead.contains(register) || registersAssigned.contains(register)) {
                    continue;
                }

                if (node.getOp().getName().startsWith("move-result")) {
                    // The target and result registers will always be identical. This makes it seem as if the register
                    // has always been read since it was read when it was in the result register.
                    continue;
                }

                if (mState.wasRegisterRead(register)) {
                    registersRead.add(register);
                } else if (mState.wasRegisterAssigned(register)) {
                    registersAssigned.add(register);
                }
            }
            stack.addAll(node.getChildren());
        }

        return Arrays.stream(registers).filter(r -> !registersRead.contains(r)).toArray();
    }

    public List<ExecutionNode> getChildren(int address) {
        List<ExecutionNode> children = new ArrayList<ExecutionNode>();
        List<ExecutionNode> nodePile = getNodePile(address);
        for (ExecutionNode node : nodePile) {
            children.addAll(node.getChildren());
        }

        return children;
    }

    public DexBuilder getDexBuilder() {
        return dexBuilder;
    }

    public @Nullable BuilderInstruction getInstruction(int address) {
        ExecutionNode node = getTemplateNode(address);

        return node.getOp().getInstruction();
    }

    public int[] getParentAddresses(int address) {
        Set<Integer> parentAddresses = new HashSet<Integer>();
        for (ExecutionNode node : getNodePile(address)) {
            ExecutionNode parent = node.getParent();
            if (null == parent) {
                continue;
            }
            parentAddresses.add(parent.getAddress());
        }

        return Ints.toArray(parentAddresses);
    }

    public List<BuilderTryBlock> getTryBlocks() {
        return implementation.getTryBlocks();
    }

    public VirtualMachine getVM() {
        return vm;
    }

    public void removeInstruction(MethodLocation location) {
        int index = location.getIndex();
        implementation.removeInstruction(index);
        removeEmptyTryCatchBlocks();
        rebuildGraph();
    }

    public void removeInstruction(int address) {
        removeInstruction(getLocation(address));
    }

    public void removeInstructions(List<Integer> addresses) {
        Collections.sort(addresses);
        Collections.reverse(addresses);

        log.debug("Removing instructions: {}", addresses);
        for (int address : addresses) {
            removeInstruction(address);
        }
    }

    public void replaceInstruction(int insertAddress, BuilderInstruction instruction) {
        List<BuilderInstruction> instructions = new LinkedList<BuilderInstruction>();
        instructions.add(instruction);
        replaceInstruction(insertAddress, instructions);
    }

    public void replaceInstruction(int insertAddress, List<BuilderInstruction> instructions) {
        recreateOrReexecute = false;
        int address = insertAddress;
        for (BuilderInstruction instruction : instructions) {
            addInstruction(address, instruction);
            address += instruction.getCodeUnits();
        }
        MethodLocation location = getLocation(address);
        recreateOrReexecute = true;
        removeInstruction(location);
    }

    public String toSmali() {
        int[] addresses = getAddresses();
        Arrays.sort(addresses);
        StringBuilder sb = new StringBuilder();
        for (int address : addresses) {
            Op op = getOp(address);
            // sb.append("#@").append(address).append('\n');
            sb.append(op.toString()).append('\n');
        }
        sb.setLength(sb.length() - 1);

        return sb.toString();
    }

    private void addToNodePile(MethodLocation newLocation) {
        // Returns node which need to be re-executed after graph / mappings are rebuilt
        // E.g. branch offset instructions can't be created without accurate mappings
        int oldIndex = newLocation.getIndex() + 1;
        MethodLocation shiftedLocation = null;
        for (MethodLocation location : locationToNodePile.keySet()) {
            if (location.getIndex() == oldIndex) {
                shiftedLocation = location;
                break;
            }
        }
        assert shiftedLocation != null;

        List<ExecutionNode> shiftedNodePile = locationToNodePile.get(shiftedLocation);
        List<ExecutionNode> newNodePile = new ArrayList<ExecutionNode>();
        locationToNodePile.put(newLocation, newNodePile);

        Op shiftedOp = shiftedNodePile.get(0).getOp();
        Op op = opCreator.create(newLocation);
        recreateLocations.add(newLocation);
        reexecuteLocations.add(newLocation);
        boolean autoAddedPadding = op instanceof NopOp && (shiftedOp instanceof FillArrayDataPayloadOp || shiftedOp instanceof SwitchPayloadOp);
        for (int i = 0; i < shiftedNodePile.size(); i++) {
            ExecutionNode newNode = new ExecutionNode(op);
            newNodePile.add(i, newNode);

            if (autoAddedPadding) {
                // Padding of this type is never reached
                break;
            }
            if (i == TEMPLATE_NODE_INDEX) {
                continue;
            }

            ExecutionNode shiftedNode = shiftedNodePile.get(i);
            ExecutionNode shiftedParent = shiftedNode.getParent();
            ExecutionContext newContext;
            if (shiftedParent != null) {
                shiftedParent.removeChild(shiftedNode);
                reparentNode(newNode, shiftedParent);

                // Recreate parent op because its children locations may be affected.
                recreateLocations.add(shiftedParent.getOp().getLocation());
            } else {
                assert METHOD_ROOT_ADDRESS == newLocation.getCodeAddress();
                newContext = vm.spawnRootExecutionContext(localMethod);
                newNode.setContext(newContext);
            }
            reparentNode(shiftedNode, newNode);
        }
    }

    private void reparentNode(@Nonnull ExecutionNode child, @Nonnull ExecutionNode parent) {
        ExecutionContext newContext = parent.getContext().spawnChild();
        child.setContext(newContext);
        child.setParent(parent);
        reexecuteLocations.add(child.getOp().getLocation());

        for (ExecutionNode grandChild : child.getChildren()) {
            grandChild.getContext().setShallowParent(newContext);
        }
    }

    private void recreateAndExecute() {
        if (!recreateOrReexecute) {
            return;
        }

        // Was removed from implementation before getting here
        recreateLocations.removeIf(p -> p.getInstruction() == null);
        reexecuteLocations.removeIf(p -> p.getInstruction() == null);

        for (MethodLocation location : recreateLocations) {
            Op op = opCreator.create(location);
            List<ExecutionNode> pile = locationToNodePile.get(location);

            // TODO: move side effects out of ops and into nodes or graph
            // This is a big ugly.
            if (op instanceof NewInstanceOp || op instanceof InvokeOp) {
                ExecutionNode node = pile.get(0);

                try {
                    SideEffect.Level originalLevel = node.getOp().getSideEffectLevel();
                    Class<? extends Op> klazz;
                    if (op instanceof NewInstanceOp) {
                        klazz = NewInstanceOp.class;
                    } else { // InvokeOp
                        klazz = InvokeOp.class;
                    }
                    Field f = klazz.getDeclaredField("sideEffectLevel");
                    f.setAccessible(true);
                    f.set(op, originalLevel);
                } catch (Exception e) {
                    // Ugly.
                    e.printStackTrace();
                }
            }

            for (int i = 0; i < pile.size(); i++) {
                pile.get(i).setOp(op);
            }
        }

        // Locations with the same address may be added. One is probably being removed. If using a sorted set with an
        // address comparator, it prevents adding multiple locations. This prevents them from executing here.
        Collections.sort(reexecuteLocations, (e1, e2) -> Integer.compare(e1.getCodeAddress(), e2.getCodeAddress()));
        Set<MethodLocation> reexecute = new LinkedHashSet<MethodLocation>(reexecuteLocations);
        for (MethodLocation location : reexecute) {
            List<ExecutionNode> pile = locationToNodePile.get(location);
            for (int i = 0; i < pile.size(); i++) {
                ExecutionNode node = pile.get(i);
                if (i == TEMPLATE_NODE_INDEX) {
                    continue;
                }

                node.execute();
            }
        }

        recreateLocations.clear();
        reexecuteLocations.clear();
    }

    private void rebuildGraph() {
        // This seems like overkill until you realize implementation may change from under us.
        // Multiple new instructions may be added from adding or removing a single instruction.
        Set<MethodLocation> staleLocations = locationToNodePile.keySet();
        Set<MethodLocation> implementationLocations = new HashSet<MethodLocation>();
        for (BuilderInstruction instruction : implementation.getInstructions()) {
            implementationLocations.add(instruction.getLocation());
        }

        Set<MethodLocation> addedLocations = new HashSet<MethodLocation>(implementationLocations);
        addedLocations.removeAll(staleLocations);
        for (MethodLocation location : addedLocations) {
            addToNodePile(location);
        }
        Set<MethodLocation> removedLocations = new HashSet<MethodLocation>(staleLocations);
        removedLocations.removeAll(implementationLocations);
        for (MethodLocation location : removedLocations) {
            removeFromNodePile(location);
        }

        TIntObjectMap<MethodLocation> newAddressToLocation = buildAddressToLocation(implementation);
        addressToLocation.clear();
        addressToLocation.putAll(newAddressToLocation);

        recreateAndExecute();
    }

    public MethodLocation getLocation(int address) {
        return addressToLocation.get(address);
    }

    @SuppressWarnings("unchecked")
    private void removeEmptyTryCatchBlocks() {
        /*
         * If every op from a try block is removed, the dex file will fail to save. Maybe dexlib should be smart enough
         * to remove empty blocks itself, but this is an admittedly strange event.
         */

        ListIterator<BuilderTryBlock> iter = implementation.getTryBlocks().listIterator();
        TIntList removeIndexes = new TIntArrayList();
        while (iter.hasNext()) {
            int index = iter.nextIndex();
            BuilderTryBlock tryBlock = iter.next();
            // Get location using reflection to avoid null check.
            MethodLocation start = getLocation(tryBlock.start);
            MethodLocation end = getLocation(tryBlock.end);
            if (start == null || end == null || start.getCodeAddress() == end.getCodeAddress()) {
                // Empty try block!

                // Went through the trouble of getting indexes ahead of time because otherwise
                // calls to equals might need to be made, and that would inspect properties
                // of the try block, which could cause null pointer exceptions.
                removeIndexes.add(index);

                // I think dexlib correctly, gracefully handles removing orphaned labels
                // if (start != null) {
                // List<Label> remove = new LinkedList<Label>();
                // remove.add(tryBlock.start);
                // remove.add(tryBlock.end);
                // start.getLabels().removeAll(remove);
                // }
            }
        }

        // MutableMethodImplementation#getTryBlocks() returns an immutable collection, but we need to modify it.
        ArrayList<BuilderTryBlock> tryBlocks = null;
        try {
            java.lang.reflect.Field f = implementation.getClass().getDeclaredField("tryBlocks");
            f.setAccessible(true); // I DO WHAT I WANT.
            tryBlocks = (ArrayList<BuilderTryBlock>) f.get(implementation);
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }

        // Remove from the end to avoid re-indexing invalidations
        removeIndexes.sort();
        removeIndexes.reverse();
        for (int index : removeIndexes.toArray()) {
            tryBlocks.remove(index);
        }
    }

    private @Nullable MethodLocation getLocation(Label label) {
        try {
            Field f = Label.class.getDeclaredField("location");
            f.setAccessible(true);
            return (MethodLocation) f.get(label);
        } catch (Exception e) {
            log.error("Couldn't get label location.", e);
        }
        return null;
    }

    private void removeFromNodePile(MethodLocation location) {
        List<ExecutionNode> nodePile = locationToNodePile.remove(location);
        Map<MethodLocation, ExecutionNode> locationToChildNodeToRemove = new HashMap<MethodLocation, ExecutionNode>();
        for (ExecutionNode removedNode : nodePile) {
            ExecutionNode parentNode = removedNode.getParent();
            if (parentNode == null) {
                continue;
            }

            parentNode.removeChild(removedNode);
            recreateLocations.add(parentNode.getOp().getLocation());
            // reexecuteLocations.add(parentNode.getOp().getLocation());

            for (ExecutionNode childNode : removedNode.getChildren()) {
                Op childOp = childNode.getOp();
                boolean pseudoChild = childOp instanceof FillArrayDataPayloadOp || childOp instanceof SwitchPayloadOp;
                if (!pseudoChild) {
                    reparentNode(childNode, parentNode);
                } else { // pseudo child
                    // Implementation was altered such that dexlib removed something, probably nop padding
                    for (ExecutionNode grandChildNode : childNode.getChildren()) {
                        reparentNode(grandChildNode, parentNode);
                    }
                    locationToChildNodeToRemove.put(childOp.getLocation(), childNode);
                }
            }
        }

        for (Entry<MethodLocation, ExecutionNode> entry : locationToChildNodeToRemove.entrySet()) {
            List<ExecutionNode> pile = locationToNodePile.get(entry.getKey());
            pile.remove(entry.getValue());
        }
    }

}
