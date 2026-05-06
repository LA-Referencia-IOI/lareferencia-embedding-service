package org.lareferencia.embedding.util;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class EmbeddingSerializationUtil {

    public static byte[] serialize(List<Double> embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.size() * Double.BYTES);
        for (Double val : embedding) {
            buffer.putDouble(val);
        }
        return buffer.array();
    }

    public static List<Double> deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        List<Double> embedding = new ArrayList<>(data.length / Double.BYTES);
        while (buffer.hasRemaining()) {
            embedding.add(buffer.getDouble());
        }
        return embedding;
    }
}
