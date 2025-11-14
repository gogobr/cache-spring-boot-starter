package com.mx.cache.util;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class CompressUtils {

    public static byte[] compress(byte[] data) {
        if (data == null || data.length == 0) return data;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {

            gzip.write(data);
            gzip.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Compression failed", e);
            return data;
        }
    }

    public static byte[] decompress(byte[] data) {
        if (data == null || data.length == 0) return data;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Decompression failed", e);
            return data;
        }
    }
}
