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
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Main {
    public static void main(String[] args)
            throws URISyntaxException, IOException, InterruptedException {
        var address = new InetSocketAddress("127.0.0.1", 8765);
        var server = HttpServer.create(address, 0);
        var zipUrl = Main.class.getResource("/jacoco.zip");
        assert zipUrl != null;
        var zs = new ZipStream(new File(zipUrl.toURI()));

        server.createContext("/", handleError(getDataHandler(zs, "jacocoHtml/")));
        server.start();
        System.out.println("Server started at http://" + address + "/");
        Thread.currentThread().join();
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

    private static Entry getEntry(ZipStream zs, String path) throws IOException {
        for (Entry entry : zs.entries()) {
            if (entry.getName().equals(path)) return entry;
        }
        return null;
    }

    private static String extension(String fileName) {
        int p1 = fileName.lastIndexOf('/');
        int p2 = fileName.lastIndexOf('.');
        if (p2 <= p1) return "";
        return fileName.substring(p2);
    }

    private static String contentType(String name) {
        switch (extension(name)) {
            case ".html": return "text/html";
            case ".gif": return "image/gif";
            case ".css": return "text/css";
            case ".js": return "text/javascript";
            default: return "application/octet-stream";
        }
    }

    private static HttpHandler getDataHandler(ZipStream zs, String prefix) {
        return exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            var path = exchange.getRequestURI().getPath();
            var suffix = path.endsWith("/") ? "index.html" : "";
            var entry = getEntry(zs, prefix + path.substring(1) + suffix);
            if (entry == null) {
                exchange.sendResponseHeaders(404, 0);
                return;
            }

            var responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Type", contentType(entry.getName()));
            long extraSize;
            InputStream is;
            switch (entry.getMethod()) {
                case DEFLATED:
                    responseHeaders.add("Content-Encoding", "gzip");
                    is = toGzip(entry);
                    extraSize = 18;
                    break;
                case STORED:
                    is = entry.rawData();
                    extraSize = 0;
                    break;
                default: exchange.sendResponseHeaders(400, 0); return;
            }
            exchange.sendResponseHeaders(200, entry.getCompressedSize() + extraSize);
            try (is; var os = exchange.getResponseBody()) {
                is.transferTo(os);
            }
        };
    }

    private static HttpHandler handleError(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (Exception e) {
                Logger logger = Logger.getGlobal();
                logger.log(Level.SEVERE, "An error occurred while handling the request", e);
                exchange.getResponseHeaders().clear();
                try {
                    exchange.sendResponseHeaders(500, 0);
                } catch (IOException ioe) {
                    logger.info("handleError failed to send headers: " + ioe);
                }
            }
            exchange.getResponseBody().close();
        };
    }
}
