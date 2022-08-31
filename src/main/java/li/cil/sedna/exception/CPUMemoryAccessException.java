package li.cil.sedna.exception;

public class CPUMemoryAccessException extends Exception {
    private final long address;
    private final int type;

    public CPUMemoryAccessException(final long address, final int type) {
        this.address = address;
        this.type = type;
    }

    public long getAddress() {
        return address;
    }

    public int getType() {
        return type;
    }
    
}
