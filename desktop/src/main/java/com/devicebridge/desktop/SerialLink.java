package com.devicebridge.desktop;

import com.devicebridge.common.transport.StreamTransport;
import com.devicebridge.common.transport.Transport;
import com.devicebridge.common.transport.TransportKind;
import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bluetooth kapcsolat a Windows által létrehozott virtuális soros (COM) porton keresztül.
 * A telefon a szabványos SPP szolgáltatással (RFCOMM) várja a kapcsolatot; párosítás után a
 * Windows "kimenő" COM portot rendel hozzá, amit itt megnyitunk.
 */
final class SerialLink {

    static final class PortInfo {
        final String systemName;
        final String description;
        final boolean bluetooth;

        PortInfo(String systemName, String description, boolean bluetooth) {
            this.systemName = systemName;
            this.description = description;
            this.bluetooth = bluetooth;
        }

        @Override
        public String toString() {
            return systemName + " – " + description;
        }
    }

    static List<PortInfo> listPorts() {
        List<PortInfo> bt = new ArrayList<>();
        List<PortInfo> other = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            String desc = p.getPortDescription() + " / " + p.getDescriptivePortName();
            boolean isBt = desc.toLowerCase(Locale.ROOT).contains("bluetooth");
            (isBt ? bt : other).add(new PortInfo(p.getSystemPortName(), p.getDescriptivePortName(), isBt));
        }
        bt.addAll(other);
        return bt;
    }

    static Transport open(PortInfo info) throws IOException {
        SerialPort port = SerialPort.getCommPort(info.systemName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 0);
        if (!port.openPort(0)) {
            throw new IOException("A(z) " + info.systemName + " port nem nyitható meg. Párosítva van a telefon, "
                    + "és fut rajta a DeviceBridge (bekapcsolt Bluetooth-szal)?");
        }
        return new StreamTransport(port.getInputStream(), port.getOutputStream(), port::closePort,
                "Bluetooth: " + info.systemName, TransportKind.BLUETOOTH);
    }
}
