package org.lareferencia.embedding.service;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

@Service
public class HashService {

  private static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();
  private static final long SEED = 0L;

  public String hash(String input) {
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    long hashValue = XX_HASH_64.hash(bytes, 0, bytes.length, SEED);
    return Long.toHexString(hashValue);
  }
}