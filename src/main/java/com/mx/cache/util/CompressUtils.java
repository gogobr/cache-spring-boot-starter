package com.mx.cache.util;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class CompressUtils {
    
    /**
     * 使用 ThreadLocal 复用 buffer，减少对象创建和 GC 压力
     */
    private static final ThreadLocal<byte[]> BUFFER_CACHE = ThreadLocal.withInitial(() -> new byte[8192]);
    
    /**
     * 最小 buffer 大小
     */
    private static final int MIN_BUFFER_SIZE = 1024;
    
    /**
     * 最大 buffer 大小
     */
    private static final int MAX_BUFFER_SIZE = 8192;

    /**
     * 压缩数据
     * 设置合理的初始容量
     *
     * @param data 待压缩数据
     * @return 压缩后的数据
     */
    public static byte[] compress(byte[] data) {
        if (data == null || data.length == 0) return data;

        try {
            // 根据数据大小设置初始容量
            int initialCapacity = Math.max(data.length / 2, 256);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(initialCapacity);
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            try {
                gzip.write(data);
                gzip.finish();
                return baos.toByteArray();
            } finally {
                gzip.close();
                baos.close();
            }
        } catch (Exception e) {
            log.error("Compression failed, data length: {}", data.length, e);
            return data;
        }
    }

    /**
     * 解压数据
     * 动态调整 buffer 大小，使用 ThreadLocal 复用 buffer
     *
     * @param data 待解压数据
     * @return 解压后的数据
     */
    public static byte[] decompress(byte[] data) {
        if (data == null || data.length == 0) return data;

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            GZIPInputStream gzip = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length * 2);
            
            try {
                // 根据数据大小动态调整 buffer，使用 ThreadLocal 复用
                int bufferSize = Math.min(Math.max(data.length, MIN_BUFFER_SIZE), MAX_BUFFER_SIZE);
                byte[] buffer = BUFFER_CACHE.get();
                if (buffer.length < bufferSize) {
                    buffer = new byte[bufferSize];
                    BUFFER_CACHE.set(buffer);
                }
                
                int len;
                while ((len = gzip.read(buffer, 0, Math.min(buffer.length, bufferSize))) > 0) {
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            } finally {
                gzip.close();
                bais.close();
                baos.close();
            }
        } catch (Exception e) {
            log.error("Decompression failed, data length: {}", data.length, e);
            return data;
        }
    }
}
