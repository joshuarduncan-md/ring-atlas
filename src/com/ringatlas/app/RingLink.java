package com.ringatlas.app;

import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.ringatlas.app.RingProtocol.*;

final class RingLink implements AutoCloseable {
    static final UUID SERVICE=uuid("0001"), WRITE=uuid("0002"), NOTIFY=uuid("0003");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static UUID uuid(String part) { return UUID.fromString("98ed"+part+"-a541-11e4-b6a0-0002a5d5c51b"); }
    record Result(String firmware,Integer battery,Integer bpm,Integer oxygen,int eventCount,int undated,boolean partial) {}
    record Signal(String type,int status,byte[] data) {}
    static final class PairingNeeded extends IOException {
        PairingNeeded(String message) { super(message); }
    }
    interface Authenticated { void save() throws Exception; }
    private final BlockingQueue<Signal> signals=new LinkedBlockingQueue<>(12000);
    private final BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private volatile boolean overflow;
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            offer(state==BluetoothProfile.STATE_CONNECTED ? "connect" : "disconnect",status,null);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) { offer("services",status,null); }
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status) { offer("mtu",status,null); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) { offer("subscribe",status,null); }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) { offer("write",status,null); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] data) {
            if (c.getUuid().equals(NOTIFY)) offer("packet",0,data.clone());
        }
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT<33 && c.getUuid().equals(NOTIFY) && c.getValue()!=null)
                offer("packet",0,c.getValue().clone());
        }
    };
    private void offer(String type,int status,byte[] data) {
        if (!signals.offer(new Signal(type,status,data))) overflow=true;
    }
    RingLink(Context context,BluetoothDevice device) throws Exception {
        gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
        if (gatt==null) throw new IOException("Android could not open a connection to the ring.");
        try {
            await("connect",18000);
            if (gatt.requestMtu(247)) {
                try { await("mtu",5000); }
                catch (IOException failure) {
                    if (!failure.getMessage().equals("The ring did not respond to mtu.")) throw failure;
                }
            }
            if (!gatt.discoverServices()) throw new IOException("Android could not start Bluetooth service discovery.");
            await("services",10000);
            BluetoothGattService service=gatt.getService(SERVICE);
            if (service==null) throw new IOException("The device did not expose the Oura Bluetooth service.");
            write=service.getCharacteristic(WRITE);
            BluetoothGattCharacteristic notify=service.getCharacteristic(NOTIFY);
            if (write==null || notify==null) throw new IOException("The ring service is missing a characteristic.");
            BluetoothGattDescriptor descriptor=notify.getDescriptor(CCCD);
            if (descriptor==null || !gatt.setCharacteristicNotification(notify,true))
                throw new IOException("Android could not enable ring notifications.");
            if (!enable(descriptor)) throw new IOException("Android declined the ring notification subscription.");
            await("subscribe",12000);
        } catch (Exception error) { close(); throw error; }
    }
    @SuppressWarnings("deprecation")
    private boolean enable(BluetoothGattDescriptor descriptor) {
        byte[] value=(descriptor.getCharacteristic().getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0
            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        if (Build.VERSION.SDK_INT>=33) return gatt.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS;
        descriptor.setValue(value); return gatt.writeDescriptor(descriptor);
    }
    private void await(String type,long timeout) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while (true) {
            Signal s=next(deadline);
            if (s==null) throw new IOException("The ring did not respond to "+type+".");
            if (s.type().equals(type)) {
                if (s.status()!=0) throw new IOException("Android Bluetooth "+type+" failed (status "+s.status()+").");
                return;
            }
        }
    }
    private Signal next(long deadline) throws Exception {
        if (overflow) throw new IOException("The Bluetooth packet queue overflowed. Retry to resume.");
        Signal s=signals.poll(Math.max(0,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
        if (s!=null && s.type().equals("disconnect"))
            throw new IOException("The ring disconnected (Bluetooth status "+s.status()+"). Retry to resume.");
        return s;
    }
    @SuppressWarnings("deprecation")
    private void send(byte[] command) throws IOException {
        boolean started;
        if (Build.VERSION.SDK_INT>=33) started=gatt.writeCharacteristic(write,command,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;
        else {
            write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); write.setValue(command);
            started=gatt.writeCharacteristic(write);
        }
        if (!started) throw new IOException("Android declined the Bluetooth write.");
    }
    private List<Packet> transact(byte[] command,Predicate<Packet> end,long maxMs) throws Exception {
        send(command);
        List<Packet> packets=new ArrayList<>();
        long now=SystemClock.elapsedRealtime(), deadline=now+maxMs, idle=now+Math.min(maxMs,9500);
        boolean written=false, done=false;
        while (!written || !done) {
            Signal s=next(Math.min(deadline,idle));
            if (s==null) {
                if (!written) throw new IOException("Android did not confirm a Bluetooth write.");
                break;
            }
            if (s.type().equals("write")) {
                if (s.status()!=0) throw new IOException("Bluetooth write failed (status "+s.status()+").");
                written=true;
            } else if (s.type().equals("packet")) {
                for (Packet p : unpack(s.data())) {
                    packets.add(p);
                    if (end.test(p)) done=true;
                }
                idle=SystemClock.elapsedRealtime()+Math.min(maxMs,9500);
            }
            if (packets.size()>20000) throw new IOException("The ring returned too many packets for one request.");
            if (SystemClock.elapsedRealtime()>=deadline) break;
        }
        if (!written) throw new IOException("Android did not confirm a Bluetooth write.");
        return packets;
    }
    private Packet one(byte[] command,int tag,int ext,long timeout) throws Exception {
        for (Packet packet : transact(command,p->p.is(tag,ext),timeout))
            if (packet.is(tag,ext)) return packet;
        return null;
    }
    private Packet one(int tag,int ext,int... command) throws Exception { return one(request(command),tag,ext,2200); }

    private int authenticate(byte[] key) throws Exception {
        Packet p=one(0x2f,0x2c,0x2f,1,0x2b);
        if (p==null || p.body().length!=16) throw new IOException("The ring did not return a supported authentication challenge.");
        Packet reply=one(auth(key,Arrays.copyOfRange(p.body(),1,16)),0x2f,0x2e,4500);
        if (reply==null || reply.body().length<2) throw new IOException("The ring did not confirm authentication.");
        return u8(reply.body()[1]);
    }

    Result sync(LocalStore store,String address,byte[] key,Consumer<String> progress,Authenticated authenticated) throws Exception {
        progress.accept("Checking ring firmware");
        Packet fw=one(9,-1,8,3,0,0,0);
        String firmware=fw!=null && fw.body().length>=6 ?
            u8(fw.body()[3])+"."+u8(fw.body()[4])+"."+u8(fw.body()[5]) : "Unknown";
        if (key==null) return new Result(firmware,battery(),null,null,0,0,false);
        progress.accept("Authenticating to the ring");
        int status=authenticate(key);
        if (status!=0) throw new IOException(status==2 ? "This ring is ready for setup. Use Connect and sync to create a key." :
            "The ring rejected this key (status "+status+"). Check that the key file belongs to this exact ring.");

        authenticated.save();
        Integer battery=battery();
        Integer bpm=latest(2), oxygen=latest(4);
        Packet hardware=one(0x19,-1,0x18,3,0x18,0,0x10);
        if (hardware!=null && hardware.body().length>1 && hardware.body()[0]==0) {
            String model=new String(hardware.body(),1,hardware.body().length-1,StandardCharsets.US_ASCII).replace("\u0000","").trim();
            if (model.endsWith("_05")) registerStream();
        }
        long now=Instant.now().getEpochSecond();
        if (one(time(now),0x13,-1,1600)==null) one(legacyTime(now),0x13,-1,1600);
        one(0x1d,-1,0x1c,1,0xbf);
        LocalStore.Checkpoint checkpoint=store.checkpoint(address);
        long cursor=checkpoint.tick();
        boolean extended=true, partial=false;
        int total=0, tail=0;
        long deadline=SystemClock.elapsedRealtime()+110000;
        while (true) {
            if (SystemClock.elapsedRealtime()>=deadline) { partial=true; break; }
            one(0x29,-1,0x28,1,0);
            Batch batch=batch(transact(history(cursor,extended),RingProtocol::terminal,45000));
            if (batch.unsupported() && extended) { extended=false; continue; }
            if (batch.unsupported()) throw new IOException("This firmware does not support the history commands.");
            long start=cursor;
            List<Event> accepted=new ArrayList<>();
            long upper=start==0 && !batch.events().isEmpty()
                ? batch.events().stream().mapToLong(Event::tick).min().getAsLong()+155_520_000L
                : start+155_520_000L;
            long next=start;
            for (Event event : batch.events()) {
                if (event.tick()<start) continue;
                if (event.tick()>upper || event.tick()>=0xffff_ffffL) throw new IOException("The ring returned an implausible timestamp. The last completed batch is saved.");
                accepted.add(event); next=Math.max(next,event.tick()+1);
            }
            if (accepted.isEmpty()) {
                if (batch.bytesLeft()!=0) throw new IOException("The ring reports pending history but did not return new events.");
                break;
            }
            store.events(address,new LocalStore.Checkpoint(checkpoint.epoch(),next),accepted);
            cursor=next; total+=accepted.size();
            progress.accept(String.format(Locale.getDefault(),"%,d events archived",total));
            if (batch.bytesLeft()==0 && accepted.size()<32) {
                if (++tail>=3) break;
            } else tail=0;
        }
        RingSummary.Result summary=RingSummary.summarize(store.events(address,checkpoint.epoch()),Instant.now().getEpochSecond(),ZoneId.systemDefault());
        store.saveDays(source(address),summary.days());
        store.saveRingBouts(source(address),summary.bouts());
        store.saveTimelines(source(address),summary.stages());
        return new Result(firmware,battery,bpm,oxygen,total,summary.undated(),partial);
    }
    static void bond(BluetoothDevice device,Consumer<String> progress) throws Exception {
        if (device.getBondState()==BluetoothDevice.BOND_BONDED) return;
        progress.accept("Accept the Android Bluetooth pairing prompt if it appears.");
        if (device.getBondState()==BluetoothDevice.BOND_NONE && !device.createBond()
                && device.getBondState()==BluetoothDevice.BOND_NONE)
            throw new IOException("Android could not start pairing. Wake the ring on its charger and try again.");
        long deadline=SystemClock.elapsedRealtime()+25000;
        while (SystemClock.elapsedRealtime()<deadline) {
            if (device.getBondState()==BluetoothDevice.BOND_BONDED) return;
            Thread.sleep(250);
        }
        throw new IOException("Android did not complete pairing. Keep the ring on its charger and approve the pairing prompt.");
    }

    Result connectOrSetUp(LocalStore store,String address,RingKey keys,Consumer<String> progress) throws Exception {
        byte[] candidate=keys.read(address);
        boolean saved=candidate!=null;
        if (!saved) { candidate=new byte[16]; new SecureRandom().nextBytes(candidate); }
        try {
            int status=authenticate(candidate);
            if (status==0 && saved) return sync(store,address,candidate,progress,()->{});
            if (status==1 || status==3)
                throw new PairingNeeded("This ring already has a key from another setup. You can import that key or back up the ring in Oura, reset it there, and let Ring Atlas set up a new key.");
            if (status!=2) throw new IOException("The ring did not confirm an unprovisioned state (status "+status+").");
            if (gatt.getDevice().getBondState()!=BluetoothDevice.BOND_BONDED)
                throw new IOException("Pair the unprovisioned ring in Android Bluetooth settings before setup.");
            progress.accept("Creating an on-device ring key");
            keys.save(address,candidate);
            LocalStore.Checkpoint prior=store.checkpoint(address);
            if (prior.tick()>0) store.events(address,new LocalStore.Checkpoint(prior.epoch()+1,0),List.of());
            byte[] payload=new byte[18]; payload[0]=0x24; payload[1]=0x10;
            System.arraycopy(candidate,0,payload,2,candidate.length);
            Packet response;
            try { response=one(payload,0x25,-1,5000); }
            finally { Arrays.fill(payload,(byte)0); }
            if (response==null || response.body().length==0 || response.body()[0]!=0)
                throw new IOException("The ring did not confirm setup. The generated key is saved locally so you can retry.");
            return sync(store,address,candidate,progress,()->{});
        } finally { Arrays.fill(candidate,(byte)0); }
    }

    int live(byte[] key,Consumer<Integer> onBeat) throws Exception {
        if (key==null) throw new IOException("Authenticate with this ring’s key before starting a live reading.");
        if (authenticate(key)!=0) throw new IOException("The ring rejected its saved key.");
        Packet state=one(0x2f,0x21,0x2f,2,0x20,2);
        if (state==null || state.body().length<6 || state.body()[1]!=2)
            throw new IOException("This ring did not report a supported heart-rate mode.");
        int previous=u8(state.body()[2]);
        if (previous>1) throw new IOException("The ring is already in a measurement session. Try again after it ends.");
        int beats=0;
        try {
            Packet reply=one(0x2f,0x23,0x2f,3,0x22,2,3);
            if (reply==null || reply.body().length<3 || reply.body()[2]!=0)
                throw new IOException("The ring did not confirm live heart rate.");
            long deadline=SystemClock.elapsedRealtime()+25000;
            while (SystemClock.elapsedRealtime()<deadline) {
                Signal signal=next(deadline);
                if (signal==null) break;
                if (!signal.type().equals("packet")) continue;
                for (Packet packet : unpack(signal.data())) {
                    Integer bpm=liveBpm(packet);
                    if (bpm!=null) { onBeat.accept(bpm); beats++; }
                }
            }
        } finally {
            // Best effort if the physical link drops, otherwise restore the original setting.
            boolean interrupted=Thread.interrupted();
            try { one(0x2f,0x23,0x2f,3,0x22,2,previous); }
            catch (Exception ignored) { }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        return beats;
    }

    static String source(String address) { return LocalStore.RING+"|"+address; }

    private void registerStream() throws Exception {
        one(0x17,-1,0x16,1,2);
        int[][] groups={{0x14,0,0x10},{0x18,0,0x10},{0x28,0,9},{0x34,0,4},{4,0,0x10},{8,0,0x10}};
        for (int[] c : groups) one(0x19,-1,0x18,3,c[0],c[1],c[2]);
        for (int id : new int[]{2,4,0x0b,0x0d,3,0x10}) one(0x2f,0x21,0x2f,2,0x20,id);
    }
    private Integer battery() throws Exception {
        Packet battery=one(0x0d,-1,0x0c,0);
        if (battery==null || battery.body().length<3 || u8(battery.body()[0])>100) return null;
        return u8(battery.body()[0]);
    }
    private Integer latest(int feature) throws Exception {
        Packet latest=one(0x2f,0x25,0x2f,2,0x24,feature);
        if (latest==null || latest.body().length<9 || latest.body()[1]!=feature || latest.body()[2]!=0) return null;
        if (feature==2) {
            int ibi=u16(latest.body(),7);
            return ibi>=300 && ibi<=2000 ? 60000/ibi : null;
        }
        if (latest.body().length<11) return null;
        int value=u8(latest.body()[10]);
        return value>=50 && value<=100 ? value : null;
    }

    @Override public void close() {
        try { gatt.disconnect(); } catch (RuntimeException ignored) { }
        try { gatt.close(); } catch (RuntimeException ignored) { }
    }
}
