package pl.zooolw.tuyapilot.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Szyfrowanie używane przez lokalny protokół Tuya: AES-128-ECB, AES-GCM, HMAC-SHA256, MD5, CRC32. */
public final class TuyaCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Klucz rozgłoszeń UDP – stały dla wszystkich urządzeń: MD5("yGAdlopoPVldABfn"). */
    public static final byte[] UDP_KEY = md5("yGAdlopoPVldABfn".getBytes(StandardCharsets.US_ASCII));

    private TuyaCrypto() { }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] pad(byte[] data) {
        int n = 16 - (data.length % 16);
        byte[] out = Arrays.copyOf(data, data.length + n);
        for (int i = data.length; i < out.length; i++) out[i] = (byte) n;
        return out;
    }

    /** Usuwa dopełnienie PKCS#7; przy błędnym dopełnieniu zgłasza wyjątek (zwykle oznacza zły klucz). */
    public static byte[] unpad(byte[] data) throws TuyaException {
        if (data.length == 0) return data;
        int n = data[data.length - 1] & 0xff;
        if (n < 1 || n > 16 || n > data.length) throw new TuyaException(TuyaException.Kind.BAD_KEY, "złe dopełnienie po odszyfrowaniu");
        for (int i = data.length - n; i < data.length; i++) {
            if ((data[i] & 0xff) != n) throw new TuyaException(TuyaException.Kind.BAD_KEY, "złe dopełnienie po odszyfrowaniu");
        }
        return Arrays.copyOf(data, data.length - n);
    }

    public static byte[] ecbEncrypt(byte[] key, byte[] data, boolean pad) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(pad ? pad(data) : data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] ecbDecrypt(byte[] key, byte[] data) throws TuyaException {
        if (data.length == 0 || data.length % 16 != 0) {
            throw new TuyaException(TuyaException.Kind.PROTOCOL, "zaszyfrowane dane mają złą długość (" + data.length + ")");
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return unpad(c.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-GCM: zwraca szyfrogram ze znacznikiem (16 B) na końcu. */
    public static byte[] gcmEncrypt(byte[] key, byte[] iv, byte[] aad, byte[] plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-GCM: wejście to szyfrogram ze znacznikiem na końcu. Zły znacznik = zły klucz albo uszkodzona ramka. */
    public static byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] aad, byte[] cipherAndTag) throws TuyaException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(cipherAndTag);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new TuyaException(TuyaException.Kind.BAD_KEY, "znacznik GCM się nie zgadza");
        } catch (GeneralSecurityException e) {
            throw new TuyaException(TuyaException.Kind.PROTOCOL, "błąd odszyfrowania GCM: " + e.getMessage());
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long crc32(byte[] data, int off, int len) {
        CRC32 c = new CRC32();
        c.update(data, off, len);
        return c.getValue();
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
