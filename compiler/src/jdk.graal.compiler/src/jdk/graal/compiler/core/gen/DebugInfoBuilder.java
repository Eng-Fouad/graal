/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.core.gen;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;

import jdk.graal.compiler.core.common.spi.MetaAccessExtensionProvider;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.CounterKey;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.lir.ConstantValue;
import jdk.graal.compiler.lir.ImplicitLIRFrameState;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.LIRValueUtil;
import jdk.graal.compiler.lir.LabelRef;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.NodeValueMap;
import jdk.graal.compiler.nodes.spi.NodeWithState;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.EscapeObjectState;
import jdk.graal.compiler.nodes.virtual.MaterializedObjectState;
import jdk.graal.compiler.nodes.virtual.VirtualBoxingNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectState;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

/**
 * Builds {@link LIRFrameState}s from {@link FrameState}s.
 */
public class DebugInfoBuilder {

    protected final NodeValueMap nodeValueMap;
    protected final MetaAccessExtensionProvider metaAccessExtensionProvider;
    protected final DebugContext debug;

    public DebugInfoBuilder(NodeValueMap nodeValueMap, MetaAccessExtensionProvider metaAccessExtensionProvider, DebugContext debug) {
        this.nodeValueMap = nodeValueMap;
        this.metaAccessExtensionProvider = metaAccessExtensionProvider;
        this.debug = debug;
    }

    private static final JavaValue[] NO_JAVA_VALUES = {};
    private static final JavaKind[] NO_JAVA_KINDS = {};

    protected final EconomicMap<VirtualObjectNode, VirtualObject> virtualObjects = EconomicMap.create(Equivalence.IDENTITY);
    protected final EconomicMap<VirtualObjectNode, EscapeObjectState> objectStates = EconomicMap.create(Equivalence.IDENTITY);

    protected final Queue<VirtualObjectNode> pendingVirtualObjects = new ArrayDeque<>();

    public LIRFrameState build(NodeWithState node, FrameState topState, LabelRef exceptionEdge, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
        assert virtualObjects.size() == 0 : virtualObjects;
        assert objectStates.size() == 0 : objectStates;
        assert pendingVirtualObjects.size() == 0 : pendingVirtualObjects;

        boolean validForDeoptimization = true;
        // collect all VirtualObjectField instances:
        FrameState current = topState;
        do {
            if (current.virtualObjectMappingCount() > 0) {
                for (EscapeObjectState state : current.virtualObjectMappings()) {
                    GraalError.guarantee(state.object() != null, "Object must be non-null %s %s", state, current);
                    if (!objectStates.containsKey(state.object())) {
                        if (!(state instanceof MaterializedObjectState) || ((MaterializedObjectState) state).materializedValue() != state.object()) {
                            objectStates.put(state.object(), state);
                        }
                    }
                }
            }
            validForDeoptimization = validForDeoptimization && current.isValidForDeoptimization();
            current = current.outerFrameState();
        } while (current != null);

        verifyFrameState(node, topState);
        BytecodeFrame frame = computeFrameForState(node, topState);

        VirtualObject[] virtualObjectsArray = null;
        if (virtualObjects.size() != 0) {
            // fill in the VirtualObject values
            VirtualObjectNode vobjNode;
            while ((vobjNode = pendingVirtualObjects.poll()) != null) {
                VirtualObject vobjValue = virtualObjects.get(vobjNode);
                assert vobjValue.getValues() == null;

                JavaValue[] values;
                JavaKind[] slotKinds;
                int entryCount = vobjNode.entryCount();
                if (entryCount == 0) {
                    values = NO_JAVA_VALUES;
                    slotKinds = NO_JAVA_KINDS;
                } else {
                    values = new JavaValue[entryCount];
                    slotKinds = new JavaKind[entryCount];
                }
                if (values.length > 0) {
                    VirtualObjectState currentField = (VirtualObjectState) objectStates.get(vobjNode);
                    assert currentField != null;
                    int pos = 0;
                    for (int i = 0; i < entryCount; i++) {
                        ValueNode value = currentField.values().get(i);
                        if (value == null) {
                            JavaKind entryKind = vobjNode.entryKind(metaAccessExtensionProvider, i);
                            values[pos] = JavaConstant.defaultForKind(entryKind.getStackKind());
                            slotKinds[pos] = entryKind.getStackKind();
                            pos++;
                        } else if (!value.isJavaConstant() || (value.asJavaConstant().getJavaKind() != JavaKind.Illegal)) {
                            values[pos] = toJavaValue(value);
                            slotKinds[pos] = toSlotKind(value);
                            pos++;
                        } else {
                            assert value.getStackKind() == JavaKind.Illegal : Assertions.errorMessage(value);
                            ValueNode previousValue = currentField.values().get(i - 1);
                            assert (previousValue != null && (previousValue.getStackKind().needsTwoSlots()) || vobjNode.isVirtualByteArray(metaAccessExtensionProvider)) : vobjNode + " " + i +
                                            " " + previousValue + " " + currentField.values().snapshot();
                            if (vobjNode.isVirtualByteArray(metaAccessExtensionProvider)) {
                                /*
                                 * Let Illegals pass through to help knowing the number of bytes to
                                 * write. For example, writing a short to index 2 of a byte array of
                                 * size 6 would look like, in debug info:
                                 *
                                 * {b0, b1, INT(...), ILLEGAL, b4, b5}
                                 *
                                 * Thus, from the VM, we can simply count the number of illegals to
                                 * restore the byte count.
                                 */
                                values[pos] = Value.ILLEGAL;
                                slotKinds[pos] = JavaKind.Illegal;
                                pos++;
                            } else if (previousValue == null || !previousValue.getStackKind().needsTwoSlots()) {
                                // Don't allow the IllegalConstant to leak into the debug info
                                JavaKind entryKind = vobjNode.entryKind(metaAccessExtensionProvider, i);
                                values[pos] = JavaConstant.defaultForKind(entryKind.getStackKind());
                                slotKinds[pos] = entryKind.getStackKind();
                                pos++;
                            }
                        }
                    }
                    if (pos != entryCount) {
                        values = Arrays.copyOf(values, pos);
                        slotKinds = Arrays.copyOf(slotKinds, pos);
                    }
                }
                assert checkValues(vobjValue.getType(), values, slotKinds);
                vobjValue.setValues(values, slotKinds);
            }

            virtualObjectsArray = new VirtualObject[virtualObjects.size()];
            int index = 0;
            for (VirtualObject value : virtualObjects.getValues()) {
                virtualObjectsArray[index++] = value;
            }
            virtualObjects.clear();
        }
        objectStates.clear();

        if (deoptReasonAndAction == null && deoptSpeculation == null) {
            return new LIRFrameState(frame, virtualObjectsArray, exceptionEdge, validForDeoptimization);
        } else {
            return new ImplicitLIRFrameState(frame, virtualObjectsArray, exceptionEdge, deoptReasonAndAction, deoptSpeculation, validForDeoptimization);
        }
    }

    private boolean checkValues(ResolvedJavaType type, JavaValue[] values, JavaKind[] slotKinds) {
        assert (values == null) == (slotKinds == null) : Assertions.errorMessage(values, slotKinds);
        if (values != null) {
            assert values.length == slotKinds.length : Assertions.errorMessage(values, slotKinds);
            if (!type.isArray()) {
                ResolvedJavaField[] fields = type.getInstanceFields(true);
                int fieldIndex = 0;
                for (int valueIndex = 0; valueIndex < values.length; valueIndex++, fieldIndex++) {
                    ResolvedJavaField field = fields[fieldIndex];
                    JavaKind valKind = slotKinds[valueIndex].getStackKind();
                    JavaKind fieldKind = storageKind(field.getType());
                    if ((valKind == JavaKind.Double || valKind == JavaKind.Long) && fieldKind == JavaKind.Int) {
                        assert fieldIndex + 1 < fields.length : String.format("Not enough fields for fieldIndex = %d valueIndex = %d %s %s", fieldIndex, valueIndex, Arrays.toString(fields),
                                        Arrays.toString(values));
                        assert storageKind(fields[fieldIndex + 1].getType()) == JavaKind.Int : String.format("fieldIndex = %d valueIndex = %d %s %s %s", fieldIndex, valueIndex,
                                        storageKind(fields[fieldIndex + 1].getType()), Arrays.toString(fields),
                                        Arrays.toString(values));
                        fieldIndex++;
                    } else {
                        assert valKind == fieldKind.getStackKind() : field + ": " + valKind + " != " + fieldKind;
                    }
                }
                assert fields.length == fieldIndex : type + ": fields=" + Arrays.toString(fields) + ", field values=" + Arrays.toString(values);
            } else {
                JavaKind componentKind = storageKind(type.getComponentType()).getStackKind();
                if (componentKind == JavaKind.Object) {
                    for (int i = 0; i < values.length; i++) {
                        assert slotKinds[i].isObject() : slotKinds[i] + " != " + componentKind;
                    }
                } else {
                    for (int i = 0; i < values.length; i++) {
                        assert slotKinds[i] == componentKind ||
                                        (slotKinds[i] == JavaKind.Illegal && storageKind(type.getComponentType()) == JavaKind.Byte) ||
                                        componentKind.getBitCount() >= slotKinds[i].getBitCount() ||
                                        (componentKind == JavaKind.Int && slotKinds[i].getBitCount() >= JavaKind.Int.getBitCount()) : slotKinds[i] + " != " + componentKind;
                    }
                }
            }
        }
        return true;
    }

    /*
     * Customization point for subclasses. For example, Word types have a kind Object, but are
     * internally stored as a primitive value. We do not know about Word types here, but subclasses
     * do know.
     */
    protected JavaKind storageKind(JavaType type) {
        return type.getJavaKind();
    }

    /**
     * Perform platform dependent verification of the FrameState.
     *
     * @param node the node using the state
     * @param topState the state
     */
    protected void verifyFrameState(NodeWithState node, FrameState topState) {
    }

    protected BytecodeFrame computeFrameForState(NodeWithState node, FrameState state) {
        try {
            assert state.bci != BytecodeFrame.INVALID_FRAMESTATE_BCI : Assertions.errorMessageContext("node", node, "state", state);
            assert state.bci != BytecodeFrame.UNKNOWN_BCI : Assertions.errorMessageContext("node", node, "state", state);

            assert state.bci != BytecodeFrame.BEFORE_BCI || state.locksSize() == 0 : Assertions.errorMessageContext("node", node, "state", state);

            assert state.bci != BytecodeFrame.AFTER_BCI || state.locksSize() == 0 : Assertions.errorMessageContext("node", node, "state", state);

            assert state.bci != BytecodeFrame.AFTER_EXCEPTION_BCI || state.locksSize() == 0 : Assertions.errorMessageContext("node", node, "state", state);

            assert !(state.getMethod().isSynchronized() && state.bci != BytecodeFrame.BEFORE_BCI && state.bci != BytecodeFrame.AFTER_BCI && state.bci != BytecodeFrame.AFTER_EXCEPTION_BCI) ||
                            !state.isValidForDeoptimization() ||
                            state.locksSize() > 0 : Assertions.errorMessageContext("state", state, "node", node, "bci", state.bci);
            assert state.verify();

            int numLocals = state.localsSize();
            int numStack = state.stackSize();
            int numLocks = state.locksSize();

            int numValues = numLocals + numStack + numLocks;
            int numKinds = numLocals + numStack;

            JavaValue[] values = numValues == 0 ? NO_JAVA_VALUES : new JavaValue[numValues];
            JavaKind[] slotKinds = numKinds == 0 ? NO_JAVA_KINDS : new JavaKind[numKinds];
            computeLocals(state, numLocals, values, slotKinds);
            computeStack(state, numLocals, numStack, values, slotKinds);
            computeLocks(state, values);

            BytecodeFrame caller = null;
            if (state.outerFrameState() != null) {
                caller = computeFrameForState(node, state.outerFrameState());
            }

            if (!state.canProduceBytecodeFrame()) {
                // This typically means a snippet or intrinsic frame state made it to the backend
                String ste = state.getCode() != null ? state.getCode().asStackTraceElement(state.bci).toString() : state.toString();
                throw new GraalError("Frame state for %s cannot be converted to a BytecodeFrame since the frame state's code is " +
                                "not the same as the frame state method's code", ste);
            }

            return new BytecodeFrame(caller, state.getMethod(), state.bci, state.getStackState().rethrowException, state.getStackState().duringCall, values, slotKinds, numLocals, numStack,
                            numLocks);
        } catch (GraalError e) {
            throw e.addContext("FrameState: ", state);
        }
    }

    protected void computeLocals(FrameState state, int numLocals, JavaValue[] values, JavaKind[] slotKinds) {
        for (int i = 0; i < numLocals; i++) {
            ValueNode local = state.localAt(i);
            values[i] = toJavaValue(local);
            slotKinds[i] = toSlotKind(local);
        }
    }

    protected void computeStack(FrameState state, int numLocals, int numStack, JavaValue[] values, JavaKind[] slotKinds) {
        for (int i = 0; i < numStack; i++) {
            ValueNode stack = state.stackAt(i);
            values[numLocals + i] = toJavaValue(stack);
            slotKinds[numLocals + i] = toSlotKind(stack);
        }
    }

    protected void computeLocks(FrameState state, JavaValue[] values) {
        for (int i = 0; i < state.locksSize(); i++) {
            values[state.localsSize() + state.stackSize() + i] = computeLockValue(state, i);
        }
    }

    protected JavaValue computeLockValue(FrameState state, int i) {
        return toJavaValue(state.lockAt(i));
    }

    private static final CounterKey STATE_VIRTUAL_OBJECTS = DebugContext.counter("StateVirtualObjects");
    private static final CounterKey STATE_ILLEGALS = DebugContext.counter("StateIllegals");
    private static final CounterKey STATE_VARIABLES = DebugContext.counter("StateVariables");
    private static final CounterKey STATE_CONSTANTS = DebugContext.counter("StateConstants");

    private static JavaKind toSlotKind(ValueNode value) {
        if (value == null) {
            return JavaKind.Illegal;
        } else {
            return value.getStackKind();
        }
    }

    protected JavaValue toJavaValue(ValueNode value) {
        try {
            if (value instanceof VirtualObjectNode) {
                VirtualObjectNode obj = (VirtualObjectNode) value;
                EscapeObjectState state = objectStates.get(obj);
                if (state == null && obj.entryCount() > 0) {
                    // null states occur for objects with 0 fields
                    throw new GraalError("no mapping found for virtual object %s", obj);
                }
                if (state instanceof MaterializedObjectState) {
                    return toJavaValue(((MaterializedObjectState) state).materializedValue());
                } else {
                    assert obj.entryCount() == 0 || state instanceof VirtualObjectState : Assertions.errorMessage(obj, obj.entryCount(), state);
                    VirtualObject vobject = virtualObjects.get(obj);
                    if (vobject == null) {
                        boolean isAutoBox = obj instanceof VirtualBoxingNode;
                        vobject = VirtualObject.get(obj.type(), virtualObjects.size(), isAutoBox);
                        virtualObjects.put(obj, vobject);
                        pendingVirtualObjects.add(obj);
                    }
                    STATE_VIRTUAL_OBJECTS.increment(debug);
                    return vobject;
                }
            } else {
                // Remove proxies from constants so the constant can be directly embedded.
                ValueNode unproxied = GraphUtil.unproxify(value);
                if (unproxied != null && unproxied.isJavaConstant()) {
                    STATE_CONSTANTS.increment(debug);
                    return unproxied.asJavaConstant();
                } else if (value != null) {
                    STATE_VARIABLES.increment(debug);
                    Value operand = nodeValueMap.operand(value);
                    if (operand instanceof ConstantValue && ((ConstantValue) operand).isJavaConstant()) {
                        return ((ConstantValue) operand).getJavaConstant();
                    } else if (LIRValueUtil.isVariable(operand)) {
                        return LIRValueUtil.asVariable(operand);
                    } else {
                        assert operand instanceof RegisterValue : operand + " for " + value;
                        return (JavaValue) operand;
                    }

                } else {
                    // return a dummy value because real value not needed
                    STATE_ILLEGALS.increment(debug);
                    return Value.ILLEGAL;
                }
            }
        } catch (GraalError e) {
            throw e.addContext("toValue: ", value);
        }
    }
}
