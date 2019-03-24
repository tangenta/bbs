package com.gaufoo.bbs.components.tokenGenerator;

import com.gaufoo.bbs.components._repositories.TokenGeneratorMemoryRepository;

import javax.xml.bind.DatatypeConverter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Random;

class TokenGeneratorImpl implements TokenGenerator {

    private final TokenGeneratorRepository repository;

    TokenGeneratorImpl(TokenGeneratorRepository repository) {
        this.repository = repository;
    }

    @Override
    public String genToken(String key, Instant expireTime) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(key.getBytes());
            md.update(String.valueOf(expireTime.toEpochMilli()).getBytes());
            md.update(randomString(16).getBytes());
            String hash = DatatypeConverter.printHexBinary(md.digest()).toUpperCase();

            repository.saveToken(hash, expireTime);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean isExpired(String token) {
        Instant expireTime = repository.getExpireTime(token);
        if (expireTime == null) return true;

        if (Instant.now().isAfter(expireTime)) {
            repository.delete(token);
            return true;
        }

        return false;
    }

    @Override
    public void expire(String token) {
        repository.delete(token);
    }

    private static final String STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdedfhijklmnopqrstuvwxyz" +
            "/*-.,;:'\"\\|[]{}()!@#$%^&`~=_0123456789";

    private static String randomString(int count) {
        StringBuilder builder = new StringBuilder();
        int length = STRING.length();
        Random random = new Random();
        while (count-- != 0) {
            int index = random.nextInt(length);
            builder.append(STRING.charAt(index));
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        TokenGenerator generator = TokenGenerator.defau1t(TokenGeneratorMemoryRepository.get());
        System.out.println(generator.genToken("123", Instant.now().plusSeconds(8000)));
    }
}
