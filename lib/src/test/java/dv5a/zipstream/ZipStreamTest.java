package dv5a.zipstream;

import dv5a.zipstream.ZipStream.Entry;
import dv5a.zipstream.ZipStream.Entry.Method;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDateTime;
import org.junit.Test;

import static org.junit.Assert.*;

public class ZipStreamTest {
    @Test
    public void macos() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        zs.entries().forEach(System.out::println);
        zs.close();
    }

    @Test
    public void i8nChars() throws IOException {
        var zs = new ZipStream(resource("/intchars.zip"));
        zs.entries().forEach(System.out::println);
        zs.close();
    }

    @Test
    public void openNonZip() {
        var nonZipFile = resource("ZipStreamTest.class");
        var thrown = assertThrows(ZipStreamException.class,
                () -> new ZipStream(nonZipFile).close());
        assertEquals("Not a zip file", thrown.getMessage());
    }

    static File resource(String resourceName) {
        var url = ZipStreamTest.class.getResource(resourceName);
        if (url == null) throw new IllegalArgumentException("resource not found: " + resourceName);
        return new File(URI.create(url.toString()));
    }

    @Test
    public void msdos() throws IOException {
        var zs = new ZipStream(resource("/MSDOS.ZIP"));
        zs.entries().forEach(System.out::println);
        zs.close();
    }

    @Test
    public void winzip() throws IOException {
        var zs = new ZipStream(resource("/winzip.zip"));
        zs.entries().forEach(System.out::println);
        zs.close();
    }

    @Test
    public void dosLegacy() throws IOException {
        var zs = new ZipStream(resource("/DOSV1.ZIP"));
        for (Entry entry : zs.entries()) {
            System.out.println(entry);
            assertEquals(Method.OTHER, entry.getMethod());
            assertEquals(6, entry.getCompressionMethod());
        }
        zs.close();
    }

    @Test
    public void empty() throws IOException {
        var zs = new ZipStream(resource("/empty.zip"));
        zs.entries().forEach(System.out::println);
        zs.close();
    }

    @Test
    public void rawData() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        for (var entry : zs.entries()) {
            try (var dataStream = entry.rawData()) {
                for (int i = 0; i < 3; i++) {
                    if (dataStream.read() < 0) break;
                }
                dataStream.transferTo(OutputStream.nullOutputStream());
            }
        }
        zs.close();
    }

    @Test
    public void rawDataOnClosedEntry() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        var entry = getEntry(zs, "java/dv5a/zipstream/ZipStream.java");
        var dataStream = entry.rawData();
        for (int i = 0; i < 3; i++) {
            if (dataStream.read() < 0) break;
        }
        dataStream.close();
        var thrown = assertThrows(IOException.class, dataStream::read);
        assertEquals("Stream closed", thrown.getMessage());
        zs.close();
    }

    @Test
    public void entries() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        var coll = zs.entries();
        int i = 0;
        for (Entry ignore : coll) {
            i++;
        }
        assertEquals(i, coll.size());
        zs.close();
    }

    @Test
    public void closed() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        var coll = zs.entries();
        zs.close();
        assertThrows(UncheckedIOException.class, () -> coll.forEach(System.out::println));
    }

    @Test
    public void entryAttributes() throws IOException {
        var zs = new ZipStream(resource("/macos.zip"));
        var entry = getEntry(zs, "java/dv5a/zipstream/ZipStream.java");
        assertEquals(0x82095c7f, entry.getCrc32());
        assertEquals(8, entry.getCompressionMethod());
        assertEquals(Method.DEFLATED, entry.getMethod());
        assertEquals(2207, entry.getCompressedSize());
        assertEquals(9374, entry.getUncompressedSize());
        assertEquals(LocalDateTime.parse("2026-02-10T15:17:22"), entry.getDateTime());

        byte[] buffer = new byte[1024];
        long totalbytes = 0;
        try (var is = entry.rawData()) {
            while (true) {
                var nread = is.read(buffer);
                if (nread < 0) break;
                totalbytes += nread;
            }
        }
        assertEquals(totalbytes, entry.getCompressedSize());
        zs.close();
    }

    private static Entry getEntry(ZipStream zs, String entryName) {
        return zs.entries()
                .stream()
                .filter(e -> entryName.equals(e.getName()))
                .findFirst()
                .orElseThrow();
    }
}
