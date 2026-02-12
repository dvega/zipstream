import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_OK;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dv5a.zipstream.ZipStream;
import dv5a.zipstream.ZipStream.Entry;
import dv5a.zipstream.ZipStream.Entry.Method;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sample usage of ZipStream. It starts a web server that serves files directly from a ZIP archive
 * without decompressing them.
 */
public class Main {
    public static void main(String[] args)
            throws IOException, InterruptedException {
        var address = new InetSocketAddress("127.0.0.1", 8765);
        var server = HttpServer.create(address, 0);
        server.setExecutor(ForkJoinPool.commonPool());
        var zipUrl = Main.class.getResource("jacoco.zip");
        assert zipUrl != null;
        var zs = new ZipStream(new File(URI.create(zipUrl.toString())));

        server.createContext("/", handleError(getDataHandler(zs)));
        server.start();
        System.out.println("Server started at http:/" + address + "/");
        Thread.currentThread().join(); // Wait for Ctrl-C
        server.stop(5);
        zs.close();
    }

    private static InputStream concat(InputStream... streams) {
        return new InputStream() {
            int index;

            @Override
            public int read() throws IOException {
                while (index < streams.length) {
                    var b = streams[index].read();
                    if (b >= 0) return b;
                    index++;
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int totread = 0;
                while (index < streams.length) {
                    var nread = streams[index].read(b, off + totread, len - totread);
                    if (nread < 0) index++;
                    else {
                        totread += nread;
                        if (nread == 0 || totread >= len) return totread;
                    }
                }
                return totread > 0 ? totread : -1;
            }

            private void close(int i) throws IOException {
                if (i >= streams.length) return;
                try (var ignore = streams[i]) {
                    close(i+1);
                }
            }

            @Override
            public void close() throws IOException {
                close(0);
            }
        };
    }

    private static final byte[] GZIP_HEADER;

    static {
        var header = new byte[10];
        header[0] = 0x1f;
        header[1] = (byte) 0x8b;
        header[2] = 8;
        header[9] = (byte) 0xFF;
        GZIP_HEADER = header;
    }

    private static InputStream toGzip(Entry entry) throws IOException {
        if (entry.getMethod() != Method.DEFLATED) {
            throw new IllegalArgumentException("Entry is not DEFLATED: " + entry.getName());
        }

        var is1 = new ByteArrayInputStream(GZIP_HEADER);
        var buff2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buff2.putInt(0, entry.getCrc32());
        buff2.putInt(4, (int) entry.getUncompressedSize());
        var is3 = new ByteArrayInputStream(buff2.array());

        return concat(is1, entry.rawData(),  is3);
    }

    private static final Map<String, Entry> ENTRY_CACHE  = new ConcurrentHashMap<>();

    private static Entry getEntry(ZipStream zs, String path) {
        return ENTRY_CACHE.computeIfAbsent(path, k -> {
            for (Entry entry : zs.entries()) {
                if (entry.getName().equals(path)) return entry;
            }
            return null;
        });
    }

    private static String extension(String fileName) {
        int p1 = fileName.lastIndexOf('.');
        if (p1 < 0) return "";
        int p2 = fileName.lastIndexOf('/');
        return p1 < p2 ? "" : fileName.substring(p2);
    }

    private static String contentType(String name) {
        switch (extension(name).toLowerCase()) {
            case ".html": return "text/html; charset=utf-8";
            case ".gif": return "image/gif";
            case ".css": return "text/css; charset=utf-8";
            case ".js": return "text/javascript";
            default: return "application/octet-stream";
        }
    }

    private static HttpHandler getDataHandler(ZipStream zs) {
        return exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(HTTP_BAD_METHOD, 0);
                return;
            }

            var contextPath = exchange.getHttpContext().getPath();
            var path = exchange.getRequestURI().getPath();
            if (path.endsWith("/")) path += "index.html";
            path = path.substring(contextPath.length());
            var entry = getEntry(zs,  path);
            if (entry == null) {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, 0);
                return;
            }

            var responseHeaders = exchange.getResponseHeaders();
            long responseLength = entry.getCompressedSize();
            InputStream is;
            switch (entry.getMethod()) {
                case DEFLATED:
                    responseHeaders.add("Content-Encoding", "gzip");
                    is = toGzip(entry);
                    responseLength += 18;
                    break;
                case STORED:
                    is = entry.rawData();
                    break;
                default: exchange.sendResponseHeaders(HTTP_NOT_IMPLEMENTED, 0); return;
            }

            responseHeaders.add("Content-Type", contentType(entry.getName()));
            var date = entry.getDateTime();
            if (date != null) {
                responseHeaders.add("Last-Modified", httpFormat(date));
            }

            exchange.sendResponseHeaders(HTTP_OK, responseLength);
            try (is; var os = exchange.getResponseBody()) {
                is.transferTo(os);
            }
        };
    }

    private static String httpFormat(LocalDateTime date) {
        var odt = date.atZone(ZoneId.systemDefault())
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(odt);
    }

    private static HttpHandler handleError(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (Exception e) {
                Logger.getGlobal()
                        .log(Level.SEVERE, "An error occurred while handling the request", e);
                exchange.getResponseHeaders().clear();
                exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, 0);
            }
            exchange.getResponseBody().close();
        };
    }
}
