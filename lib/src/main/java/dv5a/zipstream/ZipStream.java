package dv5a.zipstream;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A class for reading ZIP files.
 * This class provides functionality to open a ZIP file, iterate over its entries,
 * and access the raw data of each entry.
 */
public class ZipStream implements Closeable  {
    private final RandomAccessFile in;

    /**
     * Constructs a new ZipStream from the specified file.
     *
     * @param file the ZIP file to be opened.
     * @throws IOException if an I/O error occurs while opening the file.
     */
    public ZipStream(File file) throws IOException {
        this.in = new RandomAccessFile(file, "r");
        try {
            findEOCD();
        } catch (Throwable e) {
            try (in) {
                throw e;
            }
        }
    }

    /**
     * Closes this stream and releases any system resources associated with it.
     * If the stream is already closed, then invoking this method has no effect.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        in.close();
    }

    private static class EOCD {
        private final int entryCount;
        private final long centralDirectoryOffset;
        private final int diskNumber;

        private EOCD(int diskNumber, int entryCount, long centralDirectoryOffset) {
            this.diskNumber = diskNumber;
            this.entryCount = entryCount;
            this.centralDirectoryOffset = centralDirectoryOffset;
        }
    }

    /**
     * Represents an entry in a ZIP file.
     */
    public static class Entry {
        final RandomAccessFile in;
        final int thisDiskNumber;
        final String name;
        private final long compressedSize;
        private final long uncompressedSize;
        final long fileHeaderOffset;
        private final int crc32;
        final char time;
        final char date;
        final char method;
        final char diskNumber;
        final char nameLength;
        final char extraFieldLength;
        final char commentLength;

        /**
         * Returns the compressed size of the entry data.
         *
         * @return the compressed size.
         */
        public long getCompressedSize() {
            return compressedSize;
        }

        /**
         * Returns the uncompressed size of the entry data.
         *
         * @return the uncompressed size.
         */
        public long getUncompressedSize() {
            return uncompressedSize;
        }

        /**
         * Returns the CRC-32 checksum of the uncompressed entry data.
         *
         * @return the CRC-32 checksum.
         */
        public int getCrc32() {
            return crc32;
        }

        /**
         * Enumeration of compression methods.
         */
        public enum Method {
            /**
             * The entry is stored (no compression).
             */
            STORED,
            /**
             * The entry is deflated.
             */
            DEFLATED,
            /**
             * The entry uses another compression method.
             */
            OTHER
        }

        private Entry(RandomAccessFile in, ByteBuffer buff, FileChannel channel, int thisDiskNumber) throws IOException {
            this.in = in;
            this.thisDiskNumber = thisDiskNumber;
            if (buff.getInt(0) != 0x02014B50) throw new ZipStreamException("Invalid central directory header");
            int versionMadeBy = buff.getChar(4);
            int generalPurposeFlags = buff.getChar(8);
            this.method = buff.getChar(10);
            this.time = buff.getChar(12);
            this.date = buff.getChar(14);
            this.crc32 = buff.getInt(16);
            this.compressedSize = buff.getInt(20) & 0xFFFFFFFFL;
            this.uncompressedSize = buff.getInt(24) & 0xFFFFFFFFL;
            this.nameLength = buff.getChar(28);
            this.extraFieldLength = buff.getChar(30);
            this.commentLength = buff.getChar(32);
            this.diskNumber = buff.getChar(34);
            this.fileHeaderOffset = buff.getInt(42) & 0xFFFFFFFFL;
            var nameBuffer = ByteBuffer.allocate(this.nameLength);
            var nread = channel.read(nameBuffer);
            if (nread != nameBuffer.capacity()) throw new ZipStreamException("Incomplete read");
            Charset charset = ((versionMadeBy & 0xFF00) == 0x300 /* Unix */) || (generalPurposeFlags & 0x800) != 0
                    ? StandardCharsets.UTF_8
                    : Charset.forName("Cp437");
            this.name = new String(nameBuffer.array(), charset);
        }

        /**
         * Returns the name of the entry.
         *
         * @return the entry name.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the compression method used for this entry.
         *
         * @return the compression method as an integer.
         */
        public int getCompressionMethod() {
            return method;
        }

        /**
         * Returns the compression method used for this entry as a {@link Method} enum.
         *
         * @return the compression method.
         */
        public Method getMethod() {
            switch (method) {
                case 0: return Method.STORED;
                case 8: return Method.DEFLATED;
                default: return Method.OTHER;
            }
        }

        /**
         * Returns an InputStream for reading the raw (compressed) data of this entry.
         *
         * @return an InputStream for the raw data.
         * @throws IOException if an I/O error occurs.
         * @throws ZipStreamException if the ZIP file structure is invalid or multi-disk ZIPs are encountered.
         */
        public InputStream rawData() throws IOException {
            if (this.diskNumber != this.thisDiskNumber) {
                throw new ZipStreamException("Multi-disk ZIP files not supported");
            }
            var channel = in.getChannel();
            var buff = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            var nread = channel.read(buff, this.fileHeaderOffset);
            if (nread != buff.capacity()) throw new ZipStreamException("Incomplete read");
            if (buff.getInt(0) != 0x04034B50) throw new ZipStreamException("Invalid local directory header");
            int fileNameLength = buff.getChar(26);
            int extraFieldLength = buff.getChar(28);
            long startPos = this.fileHeaderOffset + buff.capacity() + fileNameLength + extraFieldLength;
            long compressedSize = this.compressedSize;
            return new InputStream() {
                long pos = startPos;
                @Override
                public int read() throws IOException {
                    byte[] b1 = new byte[1];
                    var nread = read(b1, 0, 1);
                    return nread == 1 ? b1[0] & 0xFF : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (pos < 0) throw new IOException("Stream closed");
                    var buff = ByteBuffer.wrap(b, off, len);
                    long remaining = compressedSize - (pos - startPos);
                    if (remaining <= 0) return -1;
                    if (remaining < len) len = (int) remaining;
                    buff.limit(off + len);
                    var nread = channel.read(buff, pos);
                    if (nread < 0) return -1;
                    pos += nread;
                    return nread;
                }

                @Override
                public void close() {
                    pos = -1;
                }
            };
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "name='" + name + '\'' +
                    ", method=" + getMethod() +
                    ", size=" + uncompressedSize +
                    '}';
        }
    }

    /**
     * Returns a collection of all entries in the ZIP file.
     *
     * @return a collection of {@link Entry} objects.
     * @throws IOException if an I/O error occurs while reading the ZIP file structure.
     */
    public Collection<Entry> entries() throws IOException {
        var eocd = findEOCD();

        return new AbstractCollection<>() {
            @Override
            public Iterator<Entry> iterator() {
                return getIterator(eocd);
            }

            @Override
            public int size() {
                return eocd.entryCount;
            }
        };
    }

    private Iterator<Entry> getIterator(EOCD eocd) {
        return new Iterator<>() {
            int count = eocd.entryCount;
            long nextOffset = eocd.centralDirectoryOffset;

            @Override
            public boolean hasNext() {
                return count > 0;
            }

            private Entry next0() throws IOException {
                if (!hasNext()) throw new NoSuchElementException();

                var buff = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                var channel = in.getChannel();
                channel.position(nextOffset);
                int nread = channel.read(buff);
                if (nread != buff.capacity()) throw new ZipStreamException("Incomplete read");
                var entry = new Entry(in, buff, channel, eocd.diskNumber);
                nextOffset += 46 + entry.nameLength + entry.extraFieldLength + entry.commentLength;
                count--;
                return entry;
            }

            @Override
            public Entry next() {
                try {
                    return next0();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private EOCD findEOCD() throws IOException {
        final int BUFF_SIZE = 1024;
        var channel = in.getChannel();
        var size = channel.size();
        if (size < 22) throw new ZipStreamException("Not a zip file");
        var buff = ByteBuffer.allocate(BUFF_SIZE + 21).order(ByteOrder.LITTLE_ENDIAN);
        var fpos = size;

        while (true) {
            var nbytes = BUFF_SIZE;
            fpos -= BUFF_SIZE;
            if (fpos < 0) {
                nbytes += (int) fpos;
                fpos = 0;
            }
            buff.position(BUFF_SIZE - nbytes);
            buff.limit(BUFF_SIZE);
            var nread = channel.read(buff, fpos);
            if (nread != nbytes) throw new ZipStreamException("Incomplete read");
            buff.limit(buff.capacity());
            for (int i = BUFF_SIZE - 1; i >= 0 ; i--) {
                if (buff.getInt(i) == 0x06054b50) {
                    char diskNumber = buff.getChar(i + 4);
                    char entryCount = buff.getChar(i + 8);
                    long centralDirectoryOffset = buff.getInt(i + 16) & 0xFFFFFFFFL;
                    return new EOCD(diskNumber, entryCount, centralDirectoryOffset);
                }
            }
            if (fpos <= 0) break;
            var array = buff.array();
            System.arraycopy(array, 0, array, BUFF_SIZE, 21);
        }
        throw new ZipStreamException("Not a zip file");
    }
}
