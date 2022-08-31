package li.cil.sedna.gdbstub;

import li.cil.sedna.exception.CPUMemoryAccessException;
import li.cil.sedna.utils.ByteBufferUtils;
import li.cil.sedna.utils.HexUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class GDBStub {
    private enum GDBState {
        DISCONNECTED,
        WAITING_FOR_COMMAND,
        STOP_REPLY
    }

    private enum StopReason {
        MESSAGE,
        BREAKPOINT
    }

    private static final Logger LOGGER = LogManager.getLogger();

    private GDBState state = GDBState.DISCONNECTED;
    private InputStream input;
    private OutputStream output;

    private final ServerSocketChannel listeningSock;
    private SocketChannel sock;

    private final CPUDebugInterface cpu;

    public GDBStub(final ServerSocketChannel socket, final CPUDebugInterface cpu) {
        this.listeningSock = socket;
        this.cpu = cpu;
        this.cpu.addBreakpointListener(this::handleBreakpointHit);
    }

    public static GDBStub createDefault(final CPUDebugInterface cpu, final int port) throws IOException {
        final ServerSocketChannel chan = ServerSocketChannel.open();
        chan.configureBlocking(false);
        chan.bind(new InetSocketAddress(port));
        return new GDBStub(chan, cpu);
    }

    public void run(final boolean waitForMessage) {
        if (isMessageAvailable() || waitForMessage) {
            runLoop(StopReason.MESSAGE);
        }
    }

    private void runLoop(final StopReason reason) {
        final ByteBuffer packetBuffer = ByteBuffer.allocate(8192);
        loop:
        while (true) {
            switch (state) {
                case DISCONNECTED -> {
                    // If we get disconnected while stopped, the CPU should stay stopped. Nothing to do
                    // but wait for a new connection
                    // If we end up needing to wait on multiple things, we can use epoll (or platform equivalent)
                    // via the Selector API
                    try {
                        this.listeningSock.configureBlocking(true);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                    this.tryConnect();
                }
                case STOP_REPLY -> {
                    try (final var s = new GDBPacketOutputStream(output);
                         final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                        w.write("S05");
                        state = GDBState.WAITING_FOR_COMMAND;
                    } catch (final IOException e) {
                        disconnect();
                    }
                }
                case WAITING_FOR_COMMAND -> {
                    try {
                        packetBuffer.clear();
                        if (!receivePacket(packetBuffer)) {
                            disconnect();
                            break;
                        }
                        if (packetBuffer.limit() == 0) continue;
                        LOGGER.debug("Packet: {}\n", asciiBytesToEscaped(packetBuffer.slice()));

                        final byte command = packetBuffer.get();
                        switch (command) {
                            case '?' -> {
                                //TODO handle different reasons
                                try (final var s = new GDBPacketOutputStream(output);
                                     final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("S05");
                                }
                            }
                            //General Query
                            case 'q' -> {
                                final byte[] Supported = "Supported:".getBytes(StandardCharsets.US_ASCII);
                                final byte[] Attached = "Attached".getBytes(StandardCharsets.US_ASCII);
                                if (ByteBufferUtils.startsWith(packetBuffer, ByteBuffer.wrap(Supported))) {
                                    packetBuffer.position(packetBuffer.position() + Supported.length);
                                    handleSupported(packetBuffer);
                                } else if (ByteBufferUtils.startsWith(packetBuffer, ByteBuffer.wrap(Attached))) {
                                    try (final var s = new GDBPacketOutputStream(output);
                                         final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                        w.write("1");
                                    }
                                } else {
                                    unknownCommand(packetBuffer);
                                }
                            }
                            case 'g' -> readGeneralRegisters();
                            case 'G' -> writeGeneralRegisters(packetBuffer);
                            case 'm' -> handleReadMemory(packetBuffer);
                            case 'M' -> handleWriteMemory(packetBuffer);
                            case 'Z' -> {
                                final byte type = packetBuffer.get();
                                switch (type) {
                                    case '0', '1' -> handleBreakpointAdd(packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'z' -> {
                                final byte type = packetBuffer.get();
                                switch (type) {
                                    case '0', '1' -> handleBreakpointRemove(packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'c' -> {
                                state = GDBState.STOP_REPLY;
                                break loop;
                            }
                            case 's' -> {
                                // We don't support the optional 'addr' parameter of the 's' packet.
                                // It appears that GDB doesn't (and never has) sent this parameter anyway.
                                if (packetBuffer.hasRemaining()) {
                                    unknownCommand(packetBuffer);
                                    return;
                                }
                                handleStep();
                            }
                            case 'D' -> {
                                try (final var s = new GDBPacketOutputStream(output);
                                     final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("OK");
                                }
                                disconnect();
                                break loop;
                            }
                            default -> unknownCommand(packetBuffer);
                        }
                    } catch (final IOException e) {
                        disconnect();
                    }
                }
            }
        }
    }

    private boolean tryConnect() {
        try {
            final SocketChannel sock = listeningSock.accept();
            if (sock == null) return false;
            this.sock = sock;
        } catch (final IOException e) {
            return false;
        }
        try {
            this.input = new BufferedInputStream(sock.socket().getInputStream());
            this.output = new BufferedOutputStream(sock.socket().getOutputStream());
            state = GDBState.WAITING_FOR_COMMAND;
            return true;
        } catch (final IOException e) {
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        try {
            LOGGER.info("GDB disconnected");
            this.state = GDBState.DISCONNECTED;
            this.sock.close();
        } catch (final IOException ignored) {
        } finally {
            this.input = null;
            this.output = null;
            this.sock = null;
        }
    }

    private boolean isMessageAvailable() {
        return switch (state) {
            case DISCONNECTED -> tryConnect();
            case WAITING_FOR_COMMAND, STOP_REPLY -> {
                try {
                    yield this.input.available() > 0;
                } catch (final IOException e) {
                    disconnect();
                    yield false;
                }
            }
        };
    }

    /**
     * While most packets are 7bit ascii, a few are binary, so we'll use a ByteBuffer.
     */
    private boolean receivePacket(final ByteBuffer buffer) {
        while (true) {
            try {
                byte actualChecksum = 0;
                while (true) {
                    final int c = input.read();

                    if (c == '$') break;
                    if (c == -1) return false;
                }
                while (true) {
                    final int c = input.read();
                    if (c == '#') break;
                    if (c == -1) return false;
                    buffer.put((byte) c);
                    actualChecksum += (byte) c;
                }
                byte expectedChecksum;
                int c;
                byte d;
                if ((c = input.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum = (byte) (d << 4);
                if ((c = input.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum |= d;

                if (actualChecksum != expectedChecksum) {
                    output.write('-');
                    output.flush();
                    continue;
                }
                output.write('+');
                output.flush();
                buffer.flip();
                return true;
            } catch (final IOException e) {
                return false;
            }
        }
    }

    private void handleBreakpointHit(final long address) {
        runLoop(StopReason.BREAKPOINT);
    }

    private void handleSupported(final ByteBuffer packet) throws IOException {
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            // Size in hex
            w.write("PacketSize=2000");
        }
    }

    private void handleReadMemory(final ByteBuffer buffer) throws IOException {
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressEnd = command.indexOf(',');
        final long address = Long.parseUnsignedLong(command, 0, addressEnd, 16);
        final int length = Integer.parseInt(command, addressEnd + 1, command.length(), 16);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            try {
                final byte[] mem = cpu.loadDebug(address, length);
                HexFormat.of().formatHex(w, mem);
            } catch (final CPUMemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleWriteMemory(final ByteBuffer buffer) throws IOException {
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressEnd = command.indexOf(',');
        final int lengthEnd = command.indexOf(':', addressEnd + 1);
        final long address = Long.parseUnsignedLong(command, 0, addressEnd, 16);
        final int length = Integer.parseInt(command, addressEnd + 1, lengthEnd, 16);
        final int actualLength = (command.length() - (lengthEnd + 1)) / 2;
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            if (length != actualLength) {
                w.write("E22");
                return;
            }
            final byte[] mem = HexFormat.of().parseHex(command, lengthEnd + 1, command.length());
            try {
                final int wrote = cpu.storeDebug(address, mem);
                if (wrote < length) {
                    w.write("E14");
                } else {
                    w.write("OK");
                }
            } catch (final CPUMemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleBreakpointAdd(final ByteBuffer buffer) throws IOException {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getLong(chars);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            cpu.addBreakpoint(address);
            w.write("OK");
        }
    }

    private void handleBreakpointRemove(final ByteBuffer buffer) throws IOException {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getLong(chars);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            cpu.removeBreakpoint(address);
            w.write("OK");
        }
    }

    private void handleStep() {
        cpu.step();
        state = GDBState.STOP_REPLY;
    }

    private String asciiBytesToEscaped(final ByteBuffer bytes) {
        final StringBuilder sb = new StringBuilder(bytes.remaining());
        while (bytes.hasRemaining()) {
            final byte b = bytes.get();
            //Printable ASCII
            if (b >= 0x20 && b <= 0x7e) {
                sb.append((char) b);
            } else {
                sb.append("\\x");
                HexFormat.of().toHexDigits(sb, b);
            }
        }
        return sb.toString();
    }

    private void unknownCommand(final ByteBuffer packet) throws IOException {
        LOGGER.debug("Unknown command: {}\n", asciiBytesToEscaped(packet.position(0)));
        // Send an empty packet
        new GDBPacketOutputStream(output).close();
    }

    private void readGeneralRegisters() throws IOException {
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            for (final long l : cpu.getGeneralRegisters()) {
                HexUtils.putLong(w, l);
            }
            HexUtils.putLong(w, cpu.getProgramCounter());
        }
    }

    private void writeGeneralRegisters(final ByteBuffer buf) throws IOException {
        final String regs = StandardCharsets.US_ASCII.decode(buf).toString();
        final ByteBuffer regsRaw = ByteBuffer.wrap(HexFormat.of().parseHex(regs)).order(ByteOrder.LITTLE_ENDIAN);
        final long[] xr = cpu.getGeneralRegisters();
        for (int i = 0; i < xr.length; i++) {
            xr[i] = regsRaw.getLong();
        }
        cpu.setProgramCounter(regsRaw.getLong());
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            w.write("OK");
        }
    }
}
