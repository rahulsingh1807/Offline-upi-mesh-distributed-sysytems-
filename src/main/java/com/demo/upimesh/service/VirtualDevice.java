package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a single phone in the simulated Bluetooth mesh.
 * Thread-safe so gossip can be parallelised without races.
 */
public class VirtualDevice {

    private final String deviceId;
    private final boolean internet;
    private final CopyOnWriteArrayList<MeshPacket> held = new CopyOnWriteArrayList<>();

    public VirtualDevice(String deviceId, boolean internet) {
        this.deviceId = deviceId;
        this.internet = internet;
    }

    public String getDeviceId() { return deviceId; }
    public boolean hasInternet() { return internet; }

    public void hold(MeshPacket packet) {
        held.add(packet);
    }

    public boolean holds(String packetId) {
        return held.stream().anyMatch(p -> p.getPacketId().equals(packetId));
    }

    public List<MeshPacket> getHeldPackets() {
        return new ArrayList<>(held);
    }

    public int packetCount() {
        return held.size();
    }

    public void clear() {
        held.clear();
    }
}
