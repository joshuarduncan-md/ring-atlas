package com.ringatlas.app;

import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

final class BackupCipher {
    private static final byte[] MAGIC={'R','I','N','G','A','T','L','1'};
    private static final int SALT=16, IV=12, LIMIT=48*1024*1024;
    private static final int ITERATIONS=600000;

    static OutputStream encrypt(OutputStream destination,char[] password) throws Exception {
        if (password.length<12) throw new IOException("Use a passphrase with at least 12 characters.");
        byte[] header=Arrays.copyOf(MAGIC,MAGIC.length+SALT+IV);
        byte[] random=new byte[SALT+IV]; new SecureRandom().nextBytes(random);
        System.arraycopy(random,0,header,MAGIC.length,random.length);
        Cipher cipher=cipher(Cipher.ENCRYPT_MODE,header,password);
        destination.write(header);
        return new CipherOutputStream(destination,cipher);
    }

    static byte[] decrypt(InputStream input,char[] password) throws Exception {
        DataInputStream stream=new DataInputStream(input);
        byte[] header=new byte[MAGIC.length+SALT+IV];
        try { stream.readFully(header); }
        catch (EOFException shortFile) { throw new IOException("This Ring Atlas backup is incomplete."); }
        if (!Arrays.equals(MAGIC,Arrays.copyOf(header,MAGIC.length)))
            throw new IOException("This file is not a supported Ring Atlas backup.");
        Cipher cipher=cipher(Cipher.DECRYPT_MODE,header,password);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] block=new byte[32768];
        for (int n;(n=stream.read(block))!=-1;) {
            if (bytes.size()+n>LIMIT) throw new IOException("This backup exceeds the 48 MB import limit.");
            bytes.write(block,0,n);
        }
        try { return cipher.doFinal(bytes.toByteArray()); }
        catch (BadPaddingException damaged) { throw new IOException("The passphrase is incorrect or the backup is damaged."); }
    }

    static OutputStream bound(OutputStream out) {
        return bound(out,LIMIT+MAGIC.length+SALT+IV,"This backup exceeds the 48 MB file limit.");
    }

    static OutputStream bound(OutputStream out,long limit,String message) {
        return new FilterOutputStream(out) {
            private long written;
            @Override public void write(int value) throws IOException {
                if (++written>limit) throw new IOException(message);
                this.out.write(value);
            }
            @Override public void write(byte[] data,int start,int count) throws IOException {
                written+=count;
                if (written>limit) throw new IOException(message);
                this.out.write(data,start,count);
            }
        };
    }

    private static Cipher cipher(int mode,byte[] header,char[] password) throws GeneralSecurityException {
        PBEKeySpec spec=new PBEKeySpec(password,Arrays.copyOfRange(header,MAGIC.length,MAGIC.length+SALT),ITERATIONS,256);
        byte[] key=null;
        try {
            key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,header,MAGIC.length+SALT,IV));
            cipher.updateAAD(header);
            return cipher;
        } finally {
            spec.clearPassword();
            if (key!=null) Arrays.fill(key,(byte)0);
        }
    }
}
