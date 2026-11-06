package com.ringatlas.app;

import java.io.*;
import java.nio.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/* Packet framing and event layouts are based on the MIT-licensed open_oura project. */
final class RingProtocol {
    record Packet(int tag,byte[] body) {
        boolean is(int tag,int ext) { return this.tag==tag && (ext<0 || body.length>0 && u8(body[0])==ext); }
    }
    record Event(long tick,int tag,byte[] body) {}
    record Batch(List<Event> events,long bytesLeft,boolean unsupported) {}

    static byte[] request(int... bytes) {
        byte[] packet=new byte[bytes.length];
        for (int i=0;i<bytes.length;i++) packet[i]=(byte)bytes[i];
        return packet;
    }
    static byte[] auth(byte[] key,byte[] nonce) throws Exception {
        if (key.length!=16 || nonce.length!=15) throw new IOException("Unsupported ring authentication challenge.");
        Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
        return ByteBuffer.allocate(19).put(request(0x2f,0x11,0x2d)).put(cipher.doFinal(nonce)).array();
    }
    static byte[] history(long tick,boolean ext) {
        return ext ? buffer(14).put(request(0x2f,12,0x41,0)).putLong(tick*100).putShort((short)4096).array()
            : buffer(11).put(request(0x10,9)).putInt((int)tick).put((byte)255).putInt(-1).array();
    }
    static byte[] legacyTime(long unix) {
        return buffer(11).put(request(0x12,9)).putLong(unix).put((byte)0).array();
    }
    static byte[] time(long unix) {
        long counter=unix/256;
        return request(0x12,9,(int)(unix*37+0xa5),(int)counter,(int)(counter>>8),(int)(counter>>16),0,0,0,0,0xf6);
    }
    static List<Packet> unpack(byte[] frame) {
        List<Packet> packets=new ArrayList<>();
        for (int i=0;i+2<=frame.length;) {
            int length=u8(frame[i+1]), end=i+2+length;
            if (end>frame.length) break;
            packets.add(new Packet(u8(frame[i]),Arrays.copyOfRange(frame,i+2,end)));
            i=end;
        }
        return packets;
    }
    static boolean terminal(Packet p) { return p.is(0x11,-1) || p.is(0x2f,0x42) || p.is(0x2f,0); }

    static Batch batch(List<Packet> packets) throws IOException {
        List<Event> events=new ArrayList<>();
        Envelope envelope=new Envelope();
        Long left=null;
        for (Packet packet : packets) {
            byte[] body=packet.body();
            if (packet.is(0x2f,0)) return new Batch(List.of(),0,true);
            if (packet.is(0x2f,0x42) && body.length>=10) {
                if (body[9]!=0) throw new IOException("Extended history request was declined (status "+u8(body[9])+").");
                left=u32(body,4);
            } else if (packet.is(0x11,-1) && body.length>=6) left=u32(body,2);
            else if (packet.is(0x2f,0x43)) envelope.feed(body,events);
            else if (packet.tag()>=0x41 && body.length>=4)
                events.add(new Event(u32(body,0),packet.tag(),Arrays.copyOfRange(body,4,body.length)));
        }
        if (left==null || envelope.position!=0) throw new IOException("The ring history batch ended before all packets arrived.");
        return new Batch(events,left,false);
    }
    private static final class Envelope {
        private final byte[] data=new byte[65537];
        private int position, expected;
        void feed(byte[] chunk,List<Event> events) throws IOException {
            for (int i=1;i<chunk.length;i++) {
                data[position++]=chunk[i];
                if (position==2) expected=u16(data,0)+2;
                if (position==expected && position>=2) {
                    decode(events); position=0; expected=0;
                }
            }
        }
        private void decode(List<Event> events) throws IOException {
            long lastMs=-1, lastTick=-1;
            int i=2;
            while (i<expected) {
                if (i+3>expected) throw new IOException("Invalid ring history envelope.");
                int tag=u8(data[i+1]), start=i+3, end=start+u8(data[i+2]);
                if (tag<0x41 || end>expected) throw new IOException("Invalid ring history event.");
                long value=0;
                int shift=0;
                boolean complete=false;
                while (start<end && shift<56) {
                    int b=u8(data[start++]);
                    value|=(long)(b&127)<<shift;
                    if ((b&128)==0) { complete=true; break; }
                    shift+=7;
                }
                if (!complete) throw new IOException("Invalid ring timestamp.");
                long ms=lastMs>=0 && value<100_000_000L ? lastMs+value : value;
                long tick=ms/100;
                if (value>0 && tick<=lastTick) tick=lastTick+1;
                if (tick>0xffff_ffffL) throw new IOException("Ring timestamp exceeds supported bounds.");
                events.add(new Event(tick,tag,Arrays.copyOfRange(data,start,end)));
                lastTick=tick; lastMs=ms; i=end;
            }
        }
    }
    static Integer liveBpm(Packet packet) {
        byte[] body=packet.body();
        if (!packet.is(0x2f,0x28) || body.length<8 || body[1]!=2) return null;
        int encoded=u16(body,6), interval=encoded&0xfff;
        return encoded>>12==1 && interval>=300 && interval<=2000 ? 60000/interval : null;
    }
    static int u8(byte b) { return b&255; }
    static int u16(byte[] b,int offset) { return u8(b[offset]) | u8(b[offset+1])<<8; }
    static long u32(byte[] b,int offset) { return (long)u16(b,offset) | (long)u16(b,offset+2)<<16; }
    private static ByteBuffer buffer(int len) { return ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN); }
    static byte[] unhex(String input) {
        String s=input.trim();
        if (s.length()%2!=0 || !s.matches("[a-fA-F0-9]*")) throw new IllegalArgumentException("Expected hexadecimal text.");
        byte[] bytes=new byte[s.length()/2];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return bytes;
    }
    static byte[] readKey(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (int value; (value=in.read())!=-1;) {
            if (out.size()==128) throw new IOException("Choose a 16-byte ring key file or a file with 32 hexadecimal characters.");
            out.write(value);
        }
        byte[] data=out.toByteArray();
        if (data.length==16) return data;
        try {
            String text=new String(data,java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (text.length()==32 && text.matches("(?i)[a-f0-9]{32}")) return unhex(text);
            throw new IOException("This is not a ring key file. Oura data exports contain measurements.");
        } finally { Arrays.fill(data,(byte)0); }
    }

    static String hex(byte[] bytes) {
        char[] chars=new char[bytes.length*2];
        String alphabet="0123456789abcdef";
        for (int i=0;i<bytes.length;i++) { int b=u8(bytes[i]); chars[i*2]=alphabet.charAt(b>>4); chars[i*2+1]=alphabet.charAt(b&15); }
        return new String(chars);
    }
}
