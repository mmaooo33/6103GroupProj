import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Manual byte-array marshalling; no stream classes or object serialization. */
public final class PayloadWriter {
    private byte[] data = new byte[64];
    private int size;

    private void reserve(int count) {
        if (count < 0 || count > Protocol.MAX_DATAGRAM_LENGTH - size)
            throw new IllegalArgumentException("Message exceeds UDP size limit");
        if (size + count > data.length)
            data = Arrays.copyOf(data, Math.max(size + count,
                    Math.min(Protocol.MAX_DATAGRAM_LENGTH, data.length * 2)));
    }

    public void writeUnsignedByte(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("u8 out of range");
        reserve(1);
        data[size++] = (byte) value;
    }

    public void writeUnsignedShort(int value) {
        if (value < 0 || value > 65535) throw new IllegalArgumentException("u16 out of range");
        reserve(2);
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) value;
    }

    public void writeInt(int value) {
        reserve(4);
        for (int shift = 24; shift >= 0; shift -= 8) data[size++] = (byte) (value >>> shift);
    }

    public void writeUnsignedInt(long value) {
        if (value < 0 || value > Protocol.MAX_REQUEST_ID)
            throw new IllegalArgumentException("u32 out of range");
        writeInt((int) value);
    }

    public void writeLong(long value) {
        reserve(8);
        for (int shift = 56; shift >= 0; shift -= 8) data[size++] = (byte) (value >>> shift);
    }

    public void writeDouble(double value) { writeLong(Double.doubleToLongBits(value)); }

    public void writeString(String value) {
        if (value == null) throw new IllegalArgumentException("Null string");
        // Avoid silently replacing unpaired UTF-16 surrogates on encoding.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Unpaired surrogate");
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired surrogate");
            }
        }
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        if (text.length > 65535) throw new IllegalArgumentException("String too long");
        reserve(2 + text.length);
        writeUnsignedShort(text.length);
        writeBytes(text);
    }

    void writeBytes(byte[] bytes) {
        reserve(bytes.length);
        System.arraycopy(bytes, 0, data, size, bytes.length);
        size += bytes.length;
    }

    public byte[] toByteArray() { return Arrays.copyOf(data, size); }
}
