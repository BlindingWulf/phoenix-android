package org.phoenix.core.world;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Central-directory checks ZIP APIs do not expose, especially Unix symlink modes. */
final class ZipCentralDirectory {
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final int MAX_EOCD_SEARCH = 22 + 65_535;

    private ZipCentralDirectory() {
    }

    static Map<String, EntryInfo> read(Path archive, int maxEntries) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            long fileSize = file.length();
            int tailLength = (int) Math.min(fileSize, MAX_EOCD_SEARCH);
            if (tailLength < 22) {
                throw new IOException("world ZIP has no end-of-central-directory record");
            }
            byte[] tail = new byte[tailLength];
            file.seek(fileSize - tailLength);
            file.readFully(tail);
            int eocdInTail = findEocd(tail);
            if (eocdInTail < 0) {
                throw new IOException("world ZIP has no valid end-of-central-directory record");
            }
            long eocdOffset = fileSize - tailLength + eocdInTail;
            file.seek(eocdOffset + 4);
            int diskNumber = readU16(file);
            int centralDisk = readU16(file);
            int entriesOnDisk = readU16(file);
            int entryCount = readU16(file);
            long centralSize = readU32(file);
            long centralOffset = readU32(file);
            int commentLength = readU16(file);
            if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
                throw new IOException("multi-disk ZIP archives are not supported");
            }
            if (entryCount == 0xffff || centralSize == 0xffff_ffffL
                    || centralOffset == 0xffff_ffffL) {
                throw new IOException("ZIP64 world archives are not supported");
            }
            if (entryCount <= 0 || entryCount > maxEntries) {
                throw new IOException("world ZIP entry count is outside the supported bound");
            }
            if (eocdInTail + 22 + commentLength != tailLength) {
                throw new IOException("world ZIP has trailing bytes or an invalid comment length");
            }
            if (centralOffset < 0 || centralSize < 0
                    || centralOffset + centralSize > eocdOffset) {
                throw new IOException("world ZIP central directory is outside the archive");
            }

            Map<String, EntryInfo> entries = new LinkedHashMap<>();
            file.seek(centralOffset);
            for (int index = 0; index < entryCount; index++) {
                if (file.getFilePointer() + 46 > centralOffset + centralSize
                        || readU32(file) != CENTRAL_SIGNATURE) {
                    throw new IOException("world ZIP central directory is malformed");
                }
                int madeBy = readU16(file);
                readU16(file); // version needed
                int flags = readU16(file);
                int method = readU16(file);
                readU16(file); // modification time
                readU16(file); // modification date
                long crc = readU32(file);
                long compressedSize = readU32(file);
                long uncompressedSize = readU32(file);
                int nameLength = readU16(file);
                int extraLength = readU16(file);
                int entryCommentLength = readU16(file);
                int diskStart = readU16(file);
                readU16(file); // internal attributes
                long externalAttributes = readU32(file);
                long localHeaderOffset = readU32(file);
                long variableEnd = file.getFilePointer() + nameLength + extraLength + entryCommentLength;
                if (variableEnd > centralOffset + centralSize || diskStart != 0) {
                    throw new IOException("world ZIP entry has invalid central-directory bounds");
                }
                if ((flags & 0x2041) != 0) {
                    throw new IOException("encrypted ZIP entries are not supported");
                }
                if (method != 0 && method != 8) {
                    throw new IOException("world ZIP uses an unsupported compression method");
                }
                if (compressedSize == 0xffff_ffffL || uncompressedSize == 0xffff_ffffL
                        || localHeaderOffset == 0xffff_ffffL) {
                    throw new IOException("ZIP64 entries are not supported");
                }
                if (localHeaderOffset + 30 > centralOffset) {
                    throw new IOException("world ZIP local header is outside the file-data region");
                }

                byte[] rawName = new byte[nameLength];
                file.readFully(rawName);
                byte[] extra = new byte[extraLength];
                file.readFully(extra);
                file.seek(file.getFilePointer() + entryCommentLength);
                rejectZip64Extra(extra);
                String name = decodeName(rawName, (flags & (1 << 11)) != 0);
                verifyLocalHeader(file, centralOffset, localHeaderOffset, name, flags, method,
                        crc, compressedSize, uncompressedSize);
                if (name.isEmpty() || entries.containsKey(name)) {
                    throw new IOException("world ZIP contains an empty or duplicate entry name");
                }

                int hostSystem = (madeBy >>> 8) & 0xff;
                int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
                int fileType = unixMode & 0xf000;
                if (hostSystem == 3 || hostSystem == 19) {
                    if (fileType == 0xa000) {
                        throw new IOException("symbolic links are not allowed in world archives");
                    }
                    if (fileType != 0 && fileType != 0x8000 && fileType != 0x4000) {
                        throw new IOException("special Unix files are not allowed in world archives");
                    }
                }
                boolean slashDirectory = name.endsWith("/");
                boolean modeDirectory = fileType == 0x4000;
                if ((fileType == 0x8000 && slashDirectory)
                        || (modeDirectory && !slashDirectory)) {
                    throw new IOException("ZIP entry path conflicts with its file type");
                }
                entries.put(name, new EntryInfo(name, compressedSize, uncompressedSize,
                        crc, method, slashDirectory || modeDirectory));
            }
            if (file.getFilePointer() != centralOffset + centralSize) {
                throw new IOException("world ZIP central directory size does not match its entries");
            }
            return Collections.unmodifiableMap(entries);
        }
    }

    private static void verifyLocalHeader(RandomAccessFile file, long centralOffset,
            long localOffset, String centralName, int centralFlags, int centralMethod,
            long centralCrc, long centralCompressed, long centralUncompressed) throws IOException {
        long resume = file.getFilePointer();
        if (localOffset < 0 || localOffset + 30 > centralOffset) {
            throw new IOException("world ZIP local header is outside the file-data region");
        }
        file.seek(localOffset);
        if (readU32(file) != LOCAL_SIGNATURE) {
            throw new IOException("world ZIP local header signature is invalid");
        }
        readU16(file); // version needed
        int flags = readU16(file);
        int method = readU16(file);
        readU16(file); // modification time
        readU16(file); // modification date
        long crc = readU32(file);
        long compressed = readU32(file);
        long uncompressed = readU32(file);
        int nameLength = readU16(file);
        int extraLength = readU16(file);
        long dataStart = file.getFilePointer() + nameLength + extraLength;
        if (flags != centralFlags || method != centralMethod || dataStart > centralOffset) {
            throw new IOException("world ZIP local header disagrees with its central directory");
        }
        byte[] rawName = new byte[nameLength];
        file.readFully(rawName);
        byte[] extra = new byte[extraLength];
        file.readFully(extra);
        rejectZip64Extra(extra);
        String localName = decodeName(rawName, (flags & (1 << 11)) != 0);
        if (!localName.equals(centralName)) {
            throw new IOException("world ZIP local and central entry names differ");
        }
        if (compressed == 0xffff_ffffL || uncompressed == 0xffff_ffffL) {
            throw new IOException("ZIP64 local entries are not supported");
        }
        if ((flags & (1 << 3)) == 0
                && (crc != centralCrc || compressed != centralCompressed
                    || uncompressed != centralUncompressed)) {
            throw new IOException("world ZIP local sizes or CRC disagree with the central directory");
        }
        if (dataStart + centralCompressed > centralOffset) {
            throw new IOException("world ZIP entry data overlaps the central directory");
        }
        file.seek(resume);
    }

    private static int findEocd(byte[] tail) {
        for (int at = tail.length - 22; at >= 0; at--) {
            if (u32(tail, at) == EOCD_SIGNATURE) {
                int commentLength = u16(tail, at + 20);
                if (at + 22 + commentLength == tail.length) {
                    return at;
                }
            }
        }
        return -1;
    }

    private static void rejectZip64Extra(byte[] extra) throws IOException {
        int at = 0;
        while (at < extra.length) {
            if (extra.length - at < 4) {
                throw new IOException("malformed ZIP extra field");
            }
            int id = u16(extra, at);
            int length = u16(extra, at + 2);
            at += 4;
            if (length > extra.length - at) {
                throw new IOException("malformed ZIP extra field length");
            }
            if (id == 0x0001) {
                throw new IOException("ZIP64 world archives are not supported");
            }
            at += length;
        }
    }

    private static String decodeName(byte[] raw, boolean utf8) throws IOException {
        Charset charset = utf8 ? StandardCharsets.UTF_8 : Charset.forName("IBM437");
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("world ZIP entry name has invalid character encoding", exception);
        }
    }

    private static int readU16(RandomAccessFile file) throws IOException {
        int low = file.readUnsignedByte();
        int high = file.readUnsignedByte();
        return low | (high << 8);
    }

    private static long readU32(RandomAccessFile file) throws IOException {
        long first = file.readUnsignedByte();
        long second = file.readUnsignedByte();
        long third = file.readUnsignedByte();
        long fourth = file.readUnsignedByte();
        return first | (second << 8) | (third << 16) | (fourth << 24);
    }

    private static int u16(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset]) | (Byte.toUnsignedInt(data[offset + 1]) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data, offset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
    }

    record EntryInfo(String name, long compressedSize, long uncompressedSize,
            long crc, int method, boolean directory) {
    }
}
