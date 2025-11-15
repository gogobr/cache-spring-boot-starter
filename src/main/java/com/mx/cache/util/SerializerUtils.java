package com.mx.cache.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

@Slf4j
public class SerializerUtils {
    private static final ThreadLocal<Kryo> KRYO_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);

        // 优化：但预先注册常用的 JDK 类，以提高序列化效率
        // 即使 setRegistrationRequired(false)，预注册的类也会使用 ID
        kryo.register(ArrayList.class);
        kryo.register(HashMap.class);
        kryo.register(HashSet.class);
        kryo.register(Date.class);

        // (例如 Order -> User -> List<Order> 的场景)
        kryo.setReferences(true);
        return kryo;
    });

    public static byte[] serialize(Object obj) {
        if (obj == null) return null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Output output = new Output(baos)) {

            KRYO_THREAD_LOCAL.get().writeObject(output, obj);
            output.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Serialization failed", e);
            return null;
        }
    }

    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        if (data == null || data.length == 0) return null;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             Input input = new Input(bais)) {

            return KRYO_THREAD_LOCAL.get().readObject(input, clazz);
        } catch (Exception e) {
            log.error("Deserialization failed", e);
            return null;
        }
    }
}
