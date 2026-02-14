# ZipStream

`ZipStream` is a small Java utility for **reading ZIP files**. It lets you:

- Open a ZIP file and **iterate over entries** (names, sizes, timestamps, CRC, compression method)
- Read an entry’s **raw (compressed) bytes**

## Sample usages
- An HTTP server that serves files directly from a ZIP archive without decompressing them
- Convert a zipped file into GZIP format without recompressing ([RFC1952](https://datatracker.ietf.org/doc/html/rfc1952))
- Convert a zipped file into ZLIB format without recompressing ([RFC1950](https://datatracker.ietf.org/doc/html/rfc1950))

## `DemoServer.java`

`DemoServer.java` demonstrates a practical use-case: **serving files directly from a ZIP archive over 
HTTP** without decompression


