package org.phoenix.core.world;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Bounded structural check for the compressed NBT Data compound in level.dat. */
final class NbtLevelDatVerifier {
    private static final int MAX_DEPTH = 64;
    private static final long MAX_TAGS = 2_000_000;

    private NbtLevelDatVerifier() {
    }

    static int verify(Path levelDat, long maxCompressedBytes, long maxExpandedBytes)
            throws IOException {
        if (Files.isSymbolicLink(levelDat)
                || !Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("world archive is missing a regular level.dat");
        }
        long compressedSize = Files.size(levelDat);
        if (compressedSize <= 2 || compressedSize > maxCompressedBytes) {
            throw new IOException("level.dat size is outside the supported bound");
        }
        try (InputStream file = Files.newInputStream(levelDat);
                GZIPInputStream gzip = new GZIPInputStream(file);
                DataInputStream input = new DataInputStream(new BoundedInputStream(gzip, maxExpandedBytes))) {
            int rootType = input.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("level.dat root tag is not an NBT compound");
            }
            String rootName = input.readUTF();
            if (!rootName.isEmpty()) {
                throw new IOException("level.dat root compound has an unexpected name");
            }
            Counter counter = new Counter();
            DataVersion dataVersion = new DataVersion();
            boolean hasData = readCompound(input, 0, true, false, counter, dataVersion);
            if (!hasData) {
                throw new IOException("level.dat has no root Data compound");
            }
            if (!dataVersion.found) {
                throw new IOException("level.dat Data compound has no integer DataVersion");
            }
            if (input.read() != -1) {
                throw new IOException("level.dat contains trailing decompressed data");
            }
            return dataVersion.value;
        } catch (EOFException exception) {
            throw new IOException("level.dat is truncated", exception);
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("level.dat is not a valid gzip NBT document", exception);
        }
    }

    private static boolean readCompound(DataInputStream input, int depth, boolean root,
            boolean dataCompound, Counter counter, DataVersion dataVersion) throws IOException {
        checkDepth(depth);
        boolean hasData = false;
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) {
                return hasData;
            }
            String name = input.readUTF();
            counter.addTag();
            if (root && name.equals("Data") && type == 10) {
                readCompound(input, depth + 1, false, true, counter, dataVersion);
                hasData = true;
            } else if (dataCompound && name.equals("DataVersion")) {
                if (type != 3) {
                    throw new IOException("level.dat DataVersion is not an integer tag");
                }
                if (dataVersion.found) {
                    throw new IOException("level.dat has duplicate DataVersion tags");
                }
                dataVersion.value = input.readInt();
                dataVersion.found = true;
            } else {
                skipPayload(input, type, depth + 1, counter);
            }
        }
    }

    private static void skipPayload(DataInputStream input, int type, int depth, Counter counter)
            throws IOException {
        checkDepth(depth);
        switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3, 5 -> input.readInt();
            case 4, 6 -> input.readLong();
            case 7 -> skipArray(input, input.readInt(), 1);
            case 8 -> input.readUTF();
            case 9 -> {
                int childType = input.readUnsignedByte();
                int length = checkedLength(input.readInt());
                if (childType == 0 && length != 0) {
                    throw new IOException("invalid NBT list with TAG_End elements");
                }
                for (int i = 0; i < length; i++) {
                    counter.addTag();
                    skipPayload(input, childType, depth + 1, counter);
                }
            }
            case 10 -> readCompound(input, depth + 1, false, false, counter, new DataVersion());
            case 11 -> skipArray(input, input.readInt(), 4);
            case 12 -> skipArray(input, input.readInt(), 8);
            default -> throw new IOException("unsupported NBT tag type " + type);
        }
    }

    private static void skipArray(DataInputStream input, int rawLength, int bytesPerElement)
            throws IOException {
        int length = checkedLength(rawLength);
        long remaining = (long) length * bytesPerElement;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("truncated NBT array");
            }
            remaining -= read;
        }
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > MAX_TAGS) {
            throw new IOException("NBT collection length is outside the supported bound");
        }
        return length;
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting exceeds the supported bound");
        }
    }

    private static final class Counter {
        private long tags;

        void addTag() throws IOException {
            if (++tags > MAX_TAGS) {
                throw new IOException("NBT tag count exceeds the supported bound");
            }
        }
    }

    private static final class DataVersion {
        private boolean found;
        private int value;
    }

    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private final long maximum;
        private long count;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int amount) throws IOException {
            count += amount;
            if (count > maximum) {
                throw new IOException("level.dat expands beyond the supported bound");
            }
        }
    }
}
