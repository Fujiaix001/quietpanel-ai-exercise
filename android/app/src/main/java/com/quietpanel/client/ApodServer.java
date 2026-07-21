package com.quietpanel.client;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

public final class ApodServer {
    private static final int PORT = 27184;
    private static final int METADATA_LIMIT = 64 * 1024;
    private static final int IMAGE_LIMIT = 6 * 1024 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public interface Listener {
        void onApodReceived(JSONObject metadata, byte[] imageBytes);
        void onApodError(String message);
    }

    private final Listener listener;
    private volatile boolean running;
    private Thread serverThread;
    private ServerSocket serverSocket;
    private Socket clientSocket;

    public ApodServer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "quietpanel-apod-server");
        serverThread.start();
    }

    public void stop() {
        Thread thread;
        synchronized (this) {
            running = false;
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
            clientSocket = null;
            serverSocket = null;
            thread = serverThread;
            serverThread = null;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runServer() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1);
            synchronized (this) {
                if (!running) {
                    closeQuietly(server);
                    return;
                }
                serverSocket = server;
            }

            while (running) {
                Socket socket = server.accept();
                synchronized (this) {
                    clientSocket = socket;
                }
                receive(socket);
                synchronized (this) {
                    if (clientSocket == socket) {
                        clientSocket = null;
                    }
                }
                closeQuietly(socket);
            }
        } catch (Exception error) {
            if (running && listener != null) {
                listener.onApodError("NASA 圖片服務錯誤：" + safeMessage(error));
            }
        } finally {
            synchronized (this) {
                closeQuietly(serverSocket);
                serverSocket = null;
            }
        }
    }

    private void receive(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'Q' || magic[1] != 'P' || magic[2] != 'A' || magic[3] != 'P') {
                throw new IllegalArgumentException("資料格式不正確");
            }

            int metadataLength = input.readInt();
            int imageLength = input.readInt();
            if (metadataLength <= 0 || metadataLength > METADATA_LIMIT
                    || imageLength <= 0 || imageLength > IMAGE_LIMIT) {
                throw new IllegalArgumentException("NASA 圖片大小超出限制");
            }

            byte[] metadataBytes = new byte[metadataLength];
            byte[] imageBytes = new byte[imageLength];
            input.readFully(metadataBytes);
            input.readFully(imageBytes);
            JSONObject metadata = new JSONObject(new String(metadataBytes, UTF_8));
            if (listener != null) {
                listener.onApodReceived(metadata, imageBytes);
            }
        } catch (Exception error) {
            if (running && listener != null) {
                listener.onApodError("NASA 圖片接收失敗：" + safeMessage(error));
            }
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
