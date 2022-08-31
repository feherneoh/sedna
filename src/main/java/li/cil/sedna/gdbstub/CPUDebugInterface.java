package li.cil.sedna.gdbstub;

import java.util.function.LongConsumer;

import li.cil.sedna.exception.CPUMemoryAccessException;

public interface CPUDebugInterface {
    long getProgramCounter();

    void setProgramCounter(long value);

    void step();

    long[] getGeneralRegisters();

    byte[] loadDebug(final long address, final int size) throws CPUMemoryAccessException;

    int storeDebug(final long address, final byte[] data) throws CPUMemoryAccessException;

    void addBreakpointListener(LongConsumer listener);

    void removeBreakpointListener(LongConsumer listener);

    void addBreakpoint(long address);

    void removeBreakpoint(long address);
}
