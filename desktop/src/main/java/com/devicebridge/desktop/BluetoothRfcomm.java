package com.devicebridge.desktop;

import com.devicebridge.common.Protocol;
import com.devicebridge.common.transport.StreamTransport;
import com.devicebridge.common.transport.Transport;
import com.devicebridge.common.transport.TransportKind;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Közvetlen Bluetooth RFCOMM kapcsolat Windowson (Winsock AF_BTH), COM port nélkül.
 *
 * <p>A Windows csak akkor hoz létre Bluetooth COM portot, ha a párosításkor a telefonon futott a
 * DeviceBridge (látszott az SPP szolgáltatás) – ez sokszor nem teljesül. Itt viszont a csatlakozáskor
 * a Windows maga kérdezi le a telefon szolgáltatásait (SDP), így elég, ha a telefon párosítva van.
 */
final class BluetoothRfcomm {
    private static final int AF_BTH = 32;
    private static final int SOCK_STREAM = 1;
    private static final int BTHPROTO_RFCOMM = 3;
    private static final long INVALID_SOCKET = -1L;
    private static final int SOCKADDR_BTH_SIZE = 30; // #pragma pack(1): USHORT + ULONGLONG + GUID + ULONG
    private static final int DEVICE_INFO_SIZE = 560;
    private static final int CONNECT_ATTEMPTS = 3;

    private BluetoothRfcomm() {}

    /** Winsock függvények (64 bites Windows: a SOCKET 8 bájtos). */
    interface Ws2 extends Library {
        int WSAStartup(short version, Pointer data);
        long socket(int af, int type, int protocol);
        int connect(long s, Pointer addr, int len);
        int recv(long s, byte[] buf, int len, int flags);
        int send(long s, byte[] buf, int len, int flags);
        int shutdown(long s, int how);
        int closesocket(long s);
    }

    interface BtApi extends Library {
        Pointer BluetoothFindFirstDevice(Pointer searchParams, Pointer deviceInfo);
        boolean BluetoothFindNextDevice(Pointer find, Pointer deviceInfo);
        boolean BluetoothFindDeviceClose(Pointer find);
    }

    private static Ws2 ws2;
    private static BtApi btApi;

    private static synchronized Ws2 ws2() throws IOException {
        if (ws2 == null) {
            try {
                Ws2 w = Native.load("ws2_32", Ws2.class);
                int rc = w.WSAStartup((short) 0x0202, new Memory(512));
                if (rc != 0) throw new IOException("WSAStartup hiba: " + rc);
                ws2 = w;
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("A Bluetooth socket nem érhető el: " + e.getMessage());
            }
        }
        return ws2;
    }

    private static synchronized BtApi btApi() {
        if (btApi == null) btApi = Native.load("bluetoothapis", BtApi.class);
        return btApi;
    }

    static boolean isSupported() {
        return SystemActions.isWindows() && "amd64".equals(System.getProperty("os.arch"));
    }

    /** Egy Windowsban párosított Bluetooth eszköz. */
    static final class Device {
        final long address;
        final String name;
        final boolean phone;

        Device(long address, String name, boolean phone) {
            this.address = address;
            this.name = name;
            this.phone = phone;
        }

        String addressText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 5; i >= 0; i--) {
                sb.append(String.format(Locale.ROOT, "%02X", (address >>> (8 * i)) & 0xff));
                if (i > 0) sb.append(':');
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return (name.isEmpty() ? "(névtelen)" : name) + " – " + addressText();
        }
    }

    /** A párosított eszközök (telefonok elöl). Nem Windowson / Bluetooth nélkül üres lista. */
    static List<Device> pairedDevices() {
        List<Device> phones = new ArrayList<>();
        List<Device> other = new ArrayList<>();
        if (!isSupported()) return phones;
        // BLUETOOTH_DEVICE_SEARCH_PARAMS (x64): dwSize, 5×BOOL, UCHAR timeout, [pad], HANDLE hRadio = 40 bájt
        Memory params = new Memory(40);
        params.clear();
        params.setInt(0, 40);
        params.setInt(4, 1);  // fReturnAuthenticated
        params.setInt(8, 1);  // fReturnRemembered
        params.setInt(12, 0); // fReturnUnknown
        params.setInt(16, 1); // fReturnConnected
        params.setInt(20, 0); // fIssueInquiry
        Memory info = new Memory(DEVICE_INFO_SIZE);
        info.clear();
        info.setInt(0, DEVICE_INFO_SIZE);
        BtApi api = btApi();
        Pointer find = api.BluetoothFindFirstDevice(params, info);
        if (find == null) return phones;
        try {
            do {
                long addr = info.getLong(8) & 0xFFFFFFFFFFFFL;
                int cod = info.getInt(16);
                String name = info.getWideString(64).trim();
                boolean phone = ((cod >> 8) & 0x1f) == 0x02; // fő eszközosztály: telefon
                (phone ? phones : other).add(new Device(addr, name, phone));
                info.clear();
                info.setInt(0, DEVICE_INFO_SIZE);
            } while (api.BluetoothFindNextDevice(find, info));
        } finally {
            api.BluetoothFindDeviceClose(find);
        }
        phones.addAll(other);
        return phones;
    }

    /** Kapcsolódás a telefon DeviceBridge (SPP) szolgáltatásához; néhányszor újrapróbálja (minden kattintásnál újra). */
    static Transport connect(Device d) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                return connectOnce(d);
            } catch (IOException e) {
                last = e;
                if (attempt < CONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Megszakítva");
                    }
                }
            }
        }
        throw last;
    }

    private static Transport connectOnce(Device d) throws IOException {
        Ws2 w = ws2();
        long s = w.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
        if (s == INVALID_SOCKET) throw new IOException("Bluetooth socket nem hozható létre (hiba " + Native.getLastError() + ")");
        Memory addr = new Memory(SOCKADDR_BTH_SIZE);
        addr.clear();
        addr.setShort(0, (short) AF_BTH);
        addr.setLong(2, d.address);
        writeGuid(addr, 10, UUID.fromString(Protocol.SPP_UUID));
        addr.setInt(26, 0); // 0 = a Windows SDP-vel keresi meg a szolgáltatás csatornáját
        if (w.connect(s, addr, SOCKADDR_BTH_SIZE) != 0) {
            int err = Native.getLastError();
            w.closesocket(s);
            throw new IOException("Bluetooth kapcsolódás sikertelen: " + d.name + " – " + explain(err));
        }
        return new StreamTransport(new SockIn(w, s), new SockOut(w, s), new SockCloser(w, s),
                "Bluetooth: " + d.name, TransportKind.BLUETOOTH);
    }

    private static String explain(int err) {
        switch (err) {
            case 10060: return "időtúllépés – a telefon nincs a közelben, vagy ki van kapcsolva a Bluetooth (hiba 10060)";
            case 10061: return "elutasítva – fut a DeviceBridge a telefonon, és van Bluetooth engedélye? (hiba 10061)";
            case 10064: return "a telefon nem elérhető (hiba 10064)";
            case 10065: return "a telefon nem érhető el – kapcsold be rajta a Bluetooth-t (hiba 10065)";
            case 10049:
            case 10108: return "a telefonon nem fut a DeviceBridge Bluetooth szolgáltatása (hiba " + err + ")";
            case 10050: return "a Windows elutasította a kapcsolatot – valószínűleg sérült a párosítás. "
                    + "Távolítsd el a telefont a Windows Bluetooth eszközei közül, a telefonon is a PC-t, "
                    + "majd párosítsd újra (hiba 10050)";
            default: return "Winsock hiba " + err;
        }
    }

    /** GUID memóriaképe: Data1 (LE), Data2 (LE), Data3 (LE), Data4[8]. */
    private static void writeGuid(Memory m, long off, UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        m.setInt(off, (int) (msb >>> 32));
        m.setShort(off + 4, (short) (msb >>> 16));
        m.setShort(off + 6, (short) msb);
        for (int i = 0; i < 8; i++) m.setByte(off + 8 + i, (byte) (lsb >>> (56 - 8 * i)));
    }

    private static final class SockCloser implements java.io.Closeable {
        private final Ws2 w;
        private final long s;
        private final AtomicBoolean closed = new AtomicBoolean();

        SockCloser(Ws2 w, long s) {
            this.w = w;
            this.s = s;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                w.shutdown(s, 2);
                w.closesocket(s); // a blokkoló recv/send hibával visszatér
            }
        }
    }

    private static final class SockIn extends InputStream {
        private final Ws2 w;
        private final long s;
        private final byte[] buf = new byte[16 * 1024];

        SockIn(Ws2 w, long s) {
            this.w = w;
            this.s = s;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int n = w.recv(s, buf, Math.min(len, buf.length), 0);
            if (n == 0) return -1;
            if (n < 0) throw new IOException("Bluetooth olvasási hiba (" + Native.getLastError() + ")");
            System.arraycopy(buf, 0, b, off, n);
            return n;
        }
    }

    private static final class SockOut extends OutputStream {
        private final Ws2 w;
        private final long s;
        private final byte[] buf = new byte[16 * 1024];

        SockOut(Ws2 w, long s) {
            this.w = w;
            this.s = s;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int chunk = Math.min(len, buf.length);
                System.arraycopy(b, off, buf, 0, chunk);
                int sent = 0;
                while (sent < chunk) {
                    byte[] part = sent == 0 ? buf : java.util.Arrays.copyOfRange(buf, sent, chunk);
                    int n = w.send(s, part, chunk - sent, 0);
                    if (n <= 0) throw new IOException("Bluetooth írási hiba (" + Native.getLastError() + ")");
                    sent += n;
                }
                off += chunk;
                len -= chunk;
            }
        }
    }
}
