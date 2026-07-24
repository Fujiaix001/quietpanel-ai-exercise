package com.quietpanel.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class WifiBeacon {
    private static final int BEACON_PORT = 27185;
    private static final int INTERVAL_MS = 2000;
    private static final String PAYLOAD = "QUIETPANEL_ANDROID_V8:27183:27184";

    private volatile boolean running;
    private Thread beaconThread;

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        beaconThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBeacon();
            }
        }, "quietpanel-wifi-beacon");
        beaconThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (beaconThread != null) {
            beaconThread.interrupt();
            beaconThread = null;
        }
    }

    private void runBeacon() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            byte[] bytes = PAYLOAD.getBytes("UTF-8");
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, broadcastAddr, BEACON_PORT);

            while (running) {
                try {
                    socket.send(packet);
                } catch (Exception ignored) {
                }
                Thread.sleep(INTERVAL_MS);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception error) {
            // Quiet fail
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
