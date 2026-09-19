package semmiedev.disc_jockey;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Reads the little endian primitives an NBS file is made of.
 * <p>
 * The whole stream is pulled into memory once. A song is parsed byte by byte, and the stream that
 * {@link java.nio.file.Files#newInputStream} returns is not buffered, so reading it directly costs
 * one read call per byte - about 2.6 million of them for a library of 35 songs.
 */
public class BinaryReader {
    private final byte[] data;
    private int position;
    private final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

    public BinaryReader(InputStream in) throws IOException {
        this.data = in.readAllBytes();
    }

    public int readInt() throws IOException {
        int start = require(Integer.BYTES);
        return buffer.clear().put(data, start, Integer.BYTES).rewind().getInt();
    }

    public long readUInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    public int readUShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    public short readShort() throws IOException {
        int start = require(Short.BYTES);
        return buffer.clear().put(data, start, Short.BYTES).rewind().getShort();
    }

    public String readString() throws IOException {
        return new String(readBytes(readInt()));
    }

    public float readFloat() throws IOException {
        int start = require(Float.BYTES);
        return buffer.clear().put(data, start, Float.BYTES).rewind().getFloat();
    }

    public byte readByte() throws IOException {
        return data[require(Byte.BYTES)];
    }

    /**
     * Returns the next {@code length} bytes as a fresh array. The fixed size primitives no longer
     * read through this method, so only the strings of a file allocate an array here.
     */
    public byte[] readBytes(int length) throws IOException {
        int start = require(length);
        return Arrays.copyOfRange(data, start, start + length);
    }

    /**
     * Reserves {@code length} bytes and returns the offset they start at. Running past the end of
     * the stream ends it with the same {@link EOFException} that reading byte by byte used to
     * throw, and a length that cannot be an array fails the way {@code new byte[length]} did.
     */
    private int require(int length) throws EOFException {
        if (length < 0) throw new NegativeArraySizeException(String.valueOf(length));
        if (data.length - position < length) throw new EOFException();
        int start = position;
        position += length;
        return start;
    }
}
