package li.cil.sedna.riscv.exception;

import li.cil.sedna.exception.CPUMemoryAccessException;

public final class R5MemoryAccessException extends CPUMemoryAccessException {

    public R5MemoryAccessException(final long address, final int type) {
        super(address, type);
    }
}
