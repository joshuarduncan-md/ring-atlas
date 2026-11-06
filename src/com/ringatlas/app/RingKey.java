package com.ringatlas.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class RingKey {
    private static final String ALIAS="ring_atlas_bluetooth_key";
    private final Context context;
    RingKey(Context context) { this.context=context; }

    void save(String address,byte[] key) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,secret());
        cipher.updateAAD(address.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv=cipher.getIV(), data=cipher.doFinal(key);
        byte[] packed=ByteBuffer.allocate(1+iv.length+data.length).put((byte)iv.length).put(iv).put(data).array();
        if (!context.getSharedPreferences("keys",0).edit().putString(address,Base64.encodeToString(packed,Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Could not store the Bluetooth key.");
    }

    byte[] read(String address) throws Exception {
        String data=context.getSharedPreferences("keys",0).getString(address,null);
        if (data==null) return null;
        ByteBuffer buffer=ByteBuffer.wrap(Base64.decode(data,Base64.NO_WRAP));
        byte[] iv=new byte[buffer.get()&255]; buffer.get(iv);
        byte[] encrypted=new byte[buffer.remaining()]; buffer.get(encrypted);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,iv));
        cipher.updateAAD(address.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted);
    }

    boolean has(String address) { return context.getSharedPreferences("keys",0).contains(address); }

    void forget() throws Exception {
        if (!context.getSharedPreferences("keys",0).edit().clear().commit()) throw new IllegalStateException("Could not clear saved ring keys.");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(ALIAS);
    }

    private SecretKey secret() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.getKey(ALIAS,null) instanceof SecretKey key) return key;
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
}
