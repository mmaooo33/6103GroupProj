import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounds-checked manual decoding. Each read advances the cursor. */
public final class PayloadReader {
    private final byte[] data;
    private int position;

    public PayloadReader(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("Null payload");
        data = payload.clone();
    }

    private void require(int count) {
        if (count < 0 || count > data.length - position)
            throw new IllegalArgumentException("Truncated field");
    }

    public int readUnsignedByte() { require(1); return data[position++] & 0xff; }
    public int readUnsignedShort() {
        require(2);
        return (readUnsignedByte() << 8) | readUnsignedByte();
    }
    public int readInt() {
        require(4);
        int result = 0;
        for (int i = 0; i < 4; i++) result = (result << 8) | readUnsignedByte();
        return result;
    }
    public long readUnsignedInt() { return Integer.toUnsignedLong(readInt()); }
    public long readLong() {
        require(8);
        long result = 0;
        for (int i = 0; i < 8; i++) result = (result << 8) | readUnsignedByte();
        return result;
    }
    public double readDouble() { return Double.longBitsToDouble(readLong()); }

    public String readString() {
        int count = readUnsignedShort();
        byte[] bytes = readBytes(count);
        String value = new String(bytes, StandardCharsets.UTF_8);
        // A valid UTF-8 string round-trips exactly; reject replacement decoding.
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Malformed UTF-8");
        return value;
    }

    byte[] readBytes(int count) {
        require(count);
        byte[] result = Arrays.copyOfRange(data, position, position + count);
        position += count;
        return result;
    }

    public void requireEnd() {
        if (position != data.length) throw new IllegalArgumentException("Unexpected trailing bytes");
    }
}
